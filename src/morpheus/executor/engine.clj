(ns morpheus.executor.engine
  "Core execution engine. Walks the DAG topologically,
   dispatches nodes, manages state, threads context,
   and communicates over a core.async event channel."
  (:require
   [clojure.core.async      :as async :refer [go go-loop chan put! <! >! close!]]
   [taoensso.timbre         :as log]
   [morpheus.graph.topo     :as topo]
   [morpheus.graph.context  :as ctx]
   [morpheus.executor.dispatch :as dispatch]))

;; ──────────────────────────────────────────
;; Run record
;; ──────────────────────────────────────────

(defn create-run
  "Returns a fresh run map. graph-atom holds the live graph
   (may grow via graph-expand). All mutable state in atoms."
  [run-id graph initial-context]
  (let [event-ch (chan 64)]
    {:run-id         run-id
     :graph-atom     (atom graph)
     :context        (atom (merge (:graph/context graph) initial-context))
     :state          (atom {})          ; node-id → :pending|:running|:done|:paused|:error
     :output-buffers (atom {})          ; node-id → accumulated stdout string (live)
     :steer-buffer   (atom nil)          ; human guidance queued for next node
     :event-ch       event-ch
     :event-mult     (async/mult event-ch) ; created once; SSE taps into this
     :resume-ch      (chan 1)              ; human action → executor (approve/revise/abort)
     :started-at     (System/currentTimeMillis)}))

;; ──────────────────────────────────────────
;; State helpers
;; ──────────────────────────────────────────

(defn- set-state! [{:keys [state event-ch]} node-id new-state]
  (swap! state assoc node-id new-state)
  (put! event-ch {:type :state-change :node-id node-id :state new-state}))

(defn- emit! [{:keys [event-ch]} event]
  (put! event-ch event))

;; ──────────────────────────────────────────
;; Node execution wrapper
;; ──────────────────────────────────────────

(defn- run-node!
  "Resolves inputs, calls dispatch, stores output.
   Returns :done, :paused, or :error."
  [{:keys [context graph-atom event-ch output-buffers] :as run} node]
  (let [default-model (get-in @graph-atom [:graph/default-model :model-id])
        node          (if (and default-model (nil? (:model node)))
                        (assoc node :model default-model)
                        node)
        _             (when output-buffers
                        (swap! output-buffers assoc (:id node) ""))
        ctx-with-buf  (assoc @context ::output-buffers output-buffers)
        resolved      (ctx/resolve-inputs (:inputs node {}) ctx-with-buf)
        steer         (first (swap-vals! (:steer-buffer run) (constantly nil)))
        ctx-final     (if steer (assoc ctx-with-buf ::steer steer) ctx-with-buf)
        start         (System/currentTimeMillis)]
    (try
      (let [output   (dispatch/execute-node!
                       node resolved ctx-final graph-atom event-ch)
            duration (- (System/currentTimeMillis) start)]
        (if (= output dispatch/checkpoint-sentinel)
          :paused
          (do
            (swap! context ctx/store-output (:output-key node) output)
            (emit! run {:type     :node-complete
                        :node-id  (:id node)
                        :duration duration})
            :done)))
      (catch Exception e
        (log/error e "Node failed" (:id node))
        (emit! run {:type    :node-error
                    :node-id (:id node)
                    :message (ex-message e)})
        :error))))

;; ──────────────────────────────────────────
;; Main executor loop
;; ──────────────────────────────────────────

(defn execute!
  "Starts the executor in a go-loop. Returns the run map immediately.
   Sends events to (:event-ch run) as nodes execute.
   Pauses at :checkpoint nodes and waits on (:resume-ch run)."
  [run-id graph initial-context]
  (let [run (create-run run-id graph initial-context)]
    (go
      (emit! run {:type :run-started :run-id run-id})
      (loop []
        (let [graph    @(:graph-atom run)
              nodes    (topo/topo-sort (:graph/nodes graph))
              runnable (topo/runnable-nodes nodes @(:state run))]
          (cond
            ;; nothing left to run and no paused nodes — done
            (and (empty? runnable)
                 (not (topo/paused? @(:state run)))
                 (topo/all-done? nodes @(:state run)))
            (emit! run {:type :run-complete :run-id run-id})

            ;; paused — wait for human resume signal
            (topo/paused? @(:state run))
            (let [action (<! (:resume-ch run))]
              (log/info "Resume action received" action)
              (case (:action action)
                :approve
                (do
                  (set-state! run (:node-id action) :done)
                  (recur))

                :revise
                (do
                  ;; inject feedback into context then re-run the node
                  (when-let [fk (get-in (topo/node-map nodes)
                                        [(:node-id action) :on-revise :inject-key])]
                    (swap! (:context run) assoc fk (:feedback action)))
                  (set-state! run (:node-id action) :pending)
                  (recur))

                :abort
                (emit! run {:type :run-aborted :run-id run-id})))

            ;; nodes are ready — run them concurrently
            (seq runnable)
            (do
              (doseq [node runnable]
                (set-state! run (:id node) :running)
                (go
                  (let [result (run-node! run node)]
                    (set-state! run (:id node) result))))
              ;; small yield to let go-blocks start
              (<! (async/timeout 50))
              (recur))

            ;; nothing runnable but not done — nodes are still :running
            :else
            (do
              (<! (async/timeout 100))
              (recur))))))
    run))

;; ──────────────────────────────────────────
;; Human action entry point
;; ──────────────────────────────────────────

(defn resume!
  "Called by the HTTP handler when a human acts on a checkpoint.
   action-map: {:action :approve|:revise|:abort
                :node-id <node-id>
                :feedback \"optional string\"}"
  [run action-map]
  (put! (:resume-ch run) action-map))

(defn steer!
  "Queues human guidance to be injected into context before the next node runs.
   Overwrites any previously queued steer. Pass nil or blank to clear."
  [run text]
  (reset! (:steer-buffer run) (when (seq text) text)))
