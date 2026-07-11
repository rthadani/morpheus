(ns morpheus.executor.engine
  "Core DAG executor. Walks the graph topologically, dispatches nodes, threads
   context, and emits events on a core.async channel."
  (:require
   [clojure.core.async      :as async :refer [go go-loop chan put! <! >! close!]]
   [taoensso.timbre         :as log]
   [morpheus.graph.topo     :as topo]
   [morpheus.graph.context  :as ctx]
   [morpheus.executor.dispatch :as dispatch]))

(defn create-run
  "Returns a fresh run map. graph-atom holds the live graph (may grow via
   graph-expand). All mutable state in atoms."
  [run-id graph initial-context]
  (let [event-ch (chan 64)]
    {:run-id         run-id
     :graph-atom     (atom graph)
     :context        (atom (merge (:graph/context graph) initial-context))
     :state          (atom {})
     :output-buffers (atom {})
     :steer-buffer   (atom nil)
     :aborted?       (atom false)
     :event-log      (atom [])
     :event-ch       event-ch
     :event-mult     (async/mult event-ch)
     :resume-ch      (chan 1)
     :started-at     (System/currentTimeMillis)}))

(defn- set-state! [{:keys [state event-ch]} node-id new-state]
  (swap! state assoc node-id new-state)
  (put! event-ch {:type :state-change :node-id node-id :state new-state}))

(defn- emit! [{:keys [event-ch event-log]} event]
  (when event-log (swap! event-log conj event))
  (put! event-ch event))

(defn- run-node!
  "Resolves inputs, calls dispatch, stores output. Returns :done | :paused | :error."
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

(defn execute!
  "Starts the executor in a go-loop. Returns the run map immediately. Sends
   events to (:event-ch run) as nodes execute. Pauses at :checkpoint nodes
   and waits on (:resume-ch run)."
  [run-id graph initial-context]
  (let [run (create-run run-id graph initial-context)]
    (go
      (emit! run {:type :run-started :run-id run-id})
      (loop []
        (let [graph    @(:graph-atom run)
              nodes    (topo/topo-sort (:graph/nodes graph))
              runnable (topo/runnable-nodes nodes @(:state run))]
          (cond
            @(:aborted? run)
            (emit! run {:type :run-aborted :run-id run-id})

            (and (empty? runnable)
                 (not (topo/paused? @(:state run)))
                 (topo/all-done? nodes @(:state run)))
            (emit! run {:type :run-complete :run-id run-id})

            (topo/paused? @(:state run))
            (let [action (<! (:resume-ch run))]
              (log/info "Resume action received" action)
              (case (:action action)
                :approve
                (do
                  (when-let [fb (not-empty (:feedback action))]
                    (let [node-id   (:node-id action)
                          all-nodes (topo/topo-sort (:graph/nodes @(:graph-atom run)))
                          node      (get (topo/node-map all-nodes) node-id)]
                      (when-let [ok (:output-key node)]
                        (swap! (:context run) ctx/store-output ok fb))))
                  (set-state! run (:node-id action) :done)
                  (recur))

                :revise
                (do
                  (when-let [fk (get-in (topo/node-map nodes)
                                        [(:node-id action) :on-revise :inject-key])]
                    (swap! (:context run) assoc fk (:feedback action)))
                  (set-state! run (:node-id action) :pending)
                  (recur))

                :abort
                (emit! run {:type :run-aborted :run-id run-id})))

            (seq runnable)
            (do
              (doseq [node runnable]
                (set-state! run (:id node) :running)
                (async/thread
                  (let [result (run-node! run node)]
                    (set-state! run (:id node) result))))
              (<! (async/timeout 50))
              (recur))

            :else
            (do
              (<! (async/timeout 100))
              (recur))))))
    run))

(defn resume!
  "Acts on a paused checkpoint. action-map: {:action :approve|:revise|:abort
                                              :node-id <id> :feedback \"...\"}"
  [run action-map]
  (put! (:resume-ch run) action-map))

(defn abort!
  "Stops the engine after the current tick. Running nodes finish naturally."
  [run]
  (reset! (:aborted? run) true))

(defn steer!
  "Queues human guidance to be injected into context before the next node runs.
   Overwrites any pending steer; pass nil/blank to clear."
  [run text]
  (let [t (when (seq text) text)]
    (reset! (:steer-buffer run) t)
    (put! (:event-ch run) {:type :steer-queued :text (or t "")})))
