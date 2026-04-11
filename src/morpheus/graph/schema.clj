(ns morpheus.graph.schema
  "EDN graph schema — pure data, no side effects.
   A graph is a map with :graph/id, :graph/nodes, optional :graph/params.
   Each node map is validated here.")

;; ──────────────────────────────────────────
;; Node types
;; ──────────────────────────────────────────

(def node-types
  #{:task          ; single LLM call
    :planning      ; LLM call that produces keyed sections + optional proposed-nodes
    :parallel      ; fan-out to N concurrent branches, fan-in with merge strategy
    :checkpoint    ; suspends execution, waits for human action
    :graph-expand  ; calls expand-fn to splice new nodes into the live graph
    :subgraph      ; runs a nested graph definition
    :shell         ; executes a shell command
    :http})        ; makes an HTTP request

(def checkpoint-actions
  #{:approve :revise :abort})

(def merge-strategies
  #{:concat :vote :first :last})

;; ──────────────────────────────────────────
;; Constructors — build valid node maps
;; ──────────────────────────────────────────

(defn base-node
  "Fields shared by all node types."
  [{:keys [id type depends-on hooks retry timeout-ms output-key]
    :or   {depends-on [] hooks {} retry {:max-attempts 1 :backoff-ms 0}}}]
  {:id          id
   :type        type
   :depends-on  depends-on
   :hooks       hooks
   :retry       retry
   :timeout-ms  timeout-ms
   :output-key  (or output-key (keyword (name id) "output"))})

(defn task-node
  [{:keys [prompt inputs model] :as opts}]
  (merge (base-node opts)
         {:prompt prompt
          :inputs (or inputs {})
          :model  model}))

(defn planning-node
  [{:keys [prompt inputs model sections] :as opts}]
  (merge (base-node opts)
         {:prompt   prompt
          :inputs   (or inputs {})
          :model    model
          :sections (or sections [])}))

(defn parallel-node
  [{:keys [branches merge] :as opts}]
  (merge (base-node opts)
         {:branches (or branches [])
          :merge    (or merge :concat)}))

(defn checkpoint-node
  [{:keys [present actions on-revise] :as opts}]
  (merge (base-node opts)
         {:present   (or present [])
          :actions   (or actions [:approve :revise :abort])
          :on-revise on-revise}))

(defn graph-expand-node
  [{:keys [expand-fn inputs] :as opts}]
  (merge (base-node opts)
         {:expand-fn expand-fn
          :inputs    (or inputs {})}))

(defn subgraph-node
  [{:keys [graph inputs] :as opts}]
  (merge (base-node opts)
         {:graph  graph
          :inputs (or inputs {})}))

;; ──────────────────────────────────────────
;; Graph constructor
;; ──────────────────────────────────────────

(defn graph
  [{:keys [id version nodes params default-model context]
    :or   {version "1.0.0" params {} context {}}}]
  {:graph/id            id
   :graph/version       version
   :graph/nodes         (vec nodes)
   :graph/params        params
   :graph/default-model default-model
   :graph/context       context})

;; ──────────────────────────────────────────
;; Validation — returns nil if valid, error map if not
;; ──────────────────────────────────────────

(defn validate-node [node]
  (cond
    (nil? (:id node))
    {:error :missing-id :node node}

    (not (node-types (:type node)))
    {:error :invalid-type :node-id (:id node) :type (:type node)}

    (and (= :checkpoint (:type node))
         (not-every? checkpoint-actions (:actions node)))
    {:error :invalid-checkpoint-actions :node-id (:id node)}

    :else nil))

(defn validate-graph [g]
  (let [node-errors (->> (:graph/nodes g)
                         (map validate-node)
                         (remove nil?))]
    (when (seq node-errors)
      {:errors node-errors})))
