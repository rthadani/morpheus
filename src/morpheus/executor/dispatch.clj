(ns morpheus.executor.dispatch
  "Multimethod dispatch for node execution.
   :task, :planning, and :parallel nodes run via Claude Code.
   :checkpoint, :graph-expand, :shell, :http remain as before."
  (:require
   [clojure.core.async          :as async]
   [clojure.string              :as str]
   [taoensso.timbre             :as log]
   [org.httpkit.client          :as http]
   [morpheus.executor.claude-code :as cc]
   [morpheus.executor.llm       :as llm]
   [morpheus.graph.context      :as ctx]
   [morpheus.graph.topo         :as topo]))

;; ──────────────────────────────────────────
;; Sentinel value returned by :checkpoint nodes
;; ──────────────────────────────────────────

(def checkpoint-sentinel ::checkpoint)

;; ──────────────────────────────────────────
;; Dispatch
;; ──────────────────────────────────────────

(defmulti execute-node!
  "Dispatch on :type. Returns output value for the node.
   Side effects: may mutate graph-atom (graph-expand), may put on event-ch."
  (fn [node _inputs _context _graph-atom _event-ch] (:type node)))

;; ──────────────────────────────────────────
;; :task — Claude Code subprocess
;;
;; Node config keys:
;;   :prompt       Template string with {{slot}} placeholders
;;   :claude-md    CLAUDE.md template (string, context keyword, or nil)
;;   :project-dir  Optional path to copy into the work dir
;;   :done-check   Optional shell command to verify completion
;;   :executor     :claude-code (default) or :llm (raw API fallback)
;; ──────────────────────────────────────────

(defmethod execute-node! :task
  [node inputs context _graph-atom event-ch]
  (log/info "Task node" (:id node) "executor:" (or (:executor node) :claude-code))
  (if (= :llm (:executor node))
    (llm/complete (or (:model node) {})
                  (ctx/render-prompt (:prompt node) inputs))
    (let [run-id    (str (System/currentTimeMillis))
          work-dir  (cc/make-work-dir! run-id (:id node))
          claude-md (cc/render-claude-md node inputs context)
          _         (cc/write-claude-md! work-dir claude-md)
          prompt    (ctx/render-prompt (:prompt node) inputs)
          result    (cc/run! {:work-dir    work-dir
                              :prompt      prompt
                              :project-dir (:project-dir node)
                              :timeout-ms  (:timeout-ms node 300000)
                              :auto?       (get node :auto? true)})]
      (when (pos? (:exit result))
        (log/warn "Claude Code non-zero exit" {:node (:id node) :exit (:exit result)}))
      (async/put! event-ch {:type     :files-written
                            :node-id  (:id node)
                            :files    (:files-written result)
                            :work-dir (:work-dir result)})
      (async/put! event-ch {:type    :node-output
                            :node-id (:id node)
                            :stdout  (:stdout result)
                            :exit    (:exit result)})
      {:output        (:stdout result)
       :files-written (:files-written result)
       :work-dir      (:work-dir result)
       :exit          (:exit result)})))

;; ──────────────────────────────────────────
;; :planning — Claude Code in plan mode
;;
;; Runs against the real codebase (read-only) to produce structured plan.
;; Parses ## node:<id> sections from output.
;;
;; Node config keys:
;;   :sections     Vec of node-id keywords to plan for
;;   :project-dir  Path to codebase — Claude Code reads it for context
;;   :claude-md    Optional framing for the planning session
;; ──────────────────────────────────────────

(defmethod execute-node! :planning
  [node inputs context _graph-atom _event-ch]
  (log/info "Planning node" (:id node))
  (let [node-ids  (map name (:sections node))
        run-id    (str (System/currentTimeMillis))
        work-dir  (cc/make-work-dir! run-id (:id node))
        claude-md (cc/render-claude-md node inputs context)
        _         (cc/write-claude-md! work-dir claude-md)
        prompt    (str (ctx/render-prompt (:prompt node) inputs)
                       "\n\nProduce a section for EACH of these node IDs:\n"
                       (str/join "\n" (map #(str "## node:" %) node-ids))
                       "\n\nUse exactly that heading format for each section.")
        result    (cc/run-plan! {:work-dir    work-dir
                                 :prompt      prompt
                                 :project-dir (:project-dir node)
                                 :timeout-ms  (:timeout-ms node 180000)})
        raw       (:stdout result)
        sections  (reduce
                    (fn [m section-id]
                      (let [pat (re-pattern
                                  (str "(?s)## node:" section-id
                                       "\\s*([\\s\\S]+?)(?=## node:|$)"))]
                        (if-let [[_ text] (re-find pat raw)]
                          (assoc m (keyword section-id) (str/trim text))
                          m)))
                    {}
                    node-ids)]
    {:summary        (str/trim (or (first (str/split raw #"## node:")) ""))
     :sections        sections
     :proposed-nodes  nil
     :work-dir        work-dir}))

;; ──────────────────────────────────────────
;; :parallel — fan-out to N concurrent Claude Code sessions
;; ──────────────────────────────────────────

(defmethod execute-node! :parallel
  [node inputs context _graph-atom event-ch]
  (log/info "Parallel node" (:id node) "branches:" (count (:branches node)))
  (let [run-id  (str (System/currentTimeMillis))
        results (doall
                  (pmap
                    (fn [branch]
                      (let [branch-inputs (ctx/resolve-inputs (:inputs branch {}) context)
                            merged        (merge inputs branch-inputs)
                            work-dir      (cc/make-work-dir! run-id (:id branch))
                            claude-md     (cc/render-claude-md branch merged context)
                            _             (cc/write-claude-md! work-dir claude-md)
                            prompt        (ctx/render-prompt (:prompt branch) merged)
                            result        (cc/run! {:work-dir    work-dir
                                                    :prompt      prompt
                                                    :project-dir (:project-dir branch)
                                                    :timeout-ms  (:timeout-ms node 300000)})]
                        (async/put! event-ch {:type    :branch-complete
                                              :node-id (:id node)
                                              :branch  (:id branch)
                                              :files   (:files-written result)})
                        {:id            (:id branch)
                         :output        (:stdout result)
                         :files-written (:files-written result)
                         :work-dir      (:work-dir result)}))
                    (:branches node)))]
    (case (or (:merge node) :concat)
      :concat {:output   (str/join "\n\n---\n\n" (map :output results))
               :branches results}
      :first  (first results)
      :last   (last results)
      {:branches results})))

;; ──────────────────────────────────────────
;; :checkpoint — suspends for human review
;; ──────────────────────────────────────────

(defmethod execute-node! :checkpoint
  [node _inputs context _graph-atom event-ch]
  (log/info "Checkpoint reached" (:id node))
  (let [presented (reduce
                    (fn [m k] (assoc m k (get context k)))
                    {}
                    (:present node))]
    (async/put! event-ch {:type      :checkpoint
                          :node-id   (:id node)
                          :presented presented
                          :actions   (:actions node)})
    checkpoint-sentinel))

;; ──────────────────────────────────────────
;; :graph-expand — splices new nodes into live graph
;; ──────────────────────────────────────────

(defmethod execute-node! :graph-expand
  [node inputs _context graph-atom _event-ch]
  (let [expand-fn (requiring-resolve (:expand-fn node))
        new-nodes (flatten (expand-fn inputs))
        _         (log/info "Expanding graph with" (count new-nodes) "new nodes")]
    (swap! graph-atom topo/splice-nodes new-nodes)
    {:node-ids (mapv :id new-nodes)}))

;; ──────────────────────────────────────────
;; :subgraph — runs a nested graph definition
;; ──────────────────────────────────────────

(defmethod execute-node! :subgraph
  [node inputs _context _graph-atom _event-ch]
  (log/info "Subgraph node" (:id node))
  (let [engine    (requiring-resolve 'morpheus.executor.engine/execute!)
        sub-graph (:graph node)
        sub-ctx   (merge inputs (or (:initial-context node) {}))
        sub-run   (engine (str (name (:id node)) "-" (System/currentTimeMillis))
                          sub-graph sub-ctx)]
    (loop []
      (let [state @(:state sub-run)
            nodes (-> @(:graph-atom sub-run) :graph/nodes)]
        (if (topo/all-done? nodes state)
          @(:context sub-run)
          (do (Thread/sleep 200) (recur)))))))

;; ──────────────────────────────────────────
;; :shell
;; ──────────────────────────────────────────

(defmethod execute-node! :shell
  [node inputs _context _graph-atom _event-ch]
  (let [cmd    (ctx/render-prompt (:command node) inputs)
        _      (log/info "Shell command" cmd)
        result (clojure.java.shell/sh "sh" "-c" cmd)]
    (when (pos? (:exit result))
      (log/warn "Shell failed" {:cmd cmd :exit (:exit result)}))
    {:stdout (:out result) :exit (:exit result)}))

;; ──────────────────────────────────────────
;; :http
;; ──────────────────────────────────────────

(defmethod execute-node! :http
  [node inputs _context _graph-atom _event-ch]
  (let [url    (ctx/render-prompt (:url node) inputs)
        method (or (:method node) :get)
        resp   @(http/request
                  {:url    url
                   :method method
                   :body   (when (:body node)
                             (clojure.data.json/write-str
                               (ctx/render-prompt (str (:body node)) inputs)))})]
    {:body (:body resp) :status (:status resp)}))

(defmethod execute-node! :default
  [node _ _ _ _]
  (throw (ex-info "Unknown node type" {:type (:type node) :id (:id node)})))
