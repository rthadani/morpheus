(ns morpheus.graph.topo
  "Topological sort and graph utilities. Pure functions — no I/O.")

(defn node-map
  "Index nodes by :id for fast lookup."
  [nodes]
  (into {} (map (juxt :id identity)) nodes))

(defn topo-sort
  "Kahn's algorithm. Returns nodes in execution order.
   Throws if a cycle is detected."
  [nodes]
  (let [nmap     (node-map nodes)
        in-edges (reduce (fn [m node]
                           (update m (:id node)
                                   #(into (or % #{}) (:depends-on node))))
                         {}
                         nodes)
        ;; nodes with no dependencies start the queue
        queue    (into (clojure.lang.PersistentQueue/EMPTY)
                       (keep (fn [[id deps]] (when (empty? deps) id))
                             in-edges))
        result   (transient [])]
    (loop [q queue edges in-edges]
      (if (empty? q)
        (if (= (count (persistent! result)) (count nodes))
          (persistent! result)
          (throw (ex-info "Cycle detected in graph"
                          {:remaining (keys edges)})))
        (let [n    (peek q)
              rest (pop q)]
          (conj! result (nmap n))
          (let [new-edges (reduce-kv
                            (fn [m id deps]
                              (let [deps' (disj deps n)]
                                (assoc m id deps')))
                            {}
                            (dissoc edges n))
                newly-free (keep (fn [[id deps]]
                                   (when (empty? deps) id))
                                 new-edges)]
            (recur (into rest newly-free) new-edges)))))))

(defn runnable-nodes
  "Returns nodes whose dependencies are all :done in state-map
   and which are currently :pending."
  [sorted-nodes state-map]
  (filter (fn [node]
            (and (= :pending (get state-map (:id node) :pending))
                 (every? #(= :done (get state-map %))
                         (:depends-on node))))
          sorted-nodes))

(defn all-done?
  "True when every node in the graph has state :done."
  [nodes state-map]
  (every? #(= :done (get state-map (:id %))) nodes))

(defn paused?
  "True when any node is :paused (awaiting human input)."
  [state-map]
  (some #(= :paused %) (vals state-map)))

(defn splice-nodes
  "Add new node definitions to a graph, preserving existing nodes."
  [graph new-nodes]
  (update graph :graph/nodes into new-nodes))
