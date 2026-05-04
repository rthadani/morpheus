(ns morpheus.graph.topo
  "Topological sort and graph utilities. Pure functions.")

(defn node-map [nodes]
  (into {} (map (juxt :id identity)) nodes))

(defn topo-sort
  "Kahn's algorithm. Returns nodes in execution order. Throws on cycle."
  [nodes]
  (let [nmap         (node-map nodes)
        in-edges     (reduce (fn [m node]
                               (update m (:id node)
                                       #(into (or % #{}) (:depends-on node))))
                             {}
                             nodes)
        initial-free (keep (fn [[id deps]] (when (empty? deps) id)) in-edges)
        result       (transient [])]
    (loop [q     (into (clojure.lang.PersistentQueue/EMPTY) initial-free)
           edges in-edges
           seen  (set initial-free)]
      (if (empty? q)
        (let [sorted (persistent! result)]
          (if (= (count sorted) (count nodes))
            sorted
            (throw (ex-info "Cycle detected in graph"
                            {:remaining (keys edges)}))))
        (let [n          (peek q)
              rest       (pop q)
              _          (conj! result (nmap n))
              new-edges  (reduce-kv
                           (fn [m id deps] (assoc m id (disj deps n)))
                           {}
                           (dissoc edges n))
              newly-free (keep (fn [[id deps]]
                                 (when (and (empty? deps) (not (seen id))) id))
                               new-edges)]
          (recur (into rest newly-free)
                 new-edges
                 (into seen newly-free)))))))

(defn runnable-nodes
  "Pending nodes whose dependencies are all :done."
  [sorted-nodes state-map]
  (filter (fn [node]
            (and (= :pending (get state-map (:id node) :pending))
                 (every? #(= :done (get state-map %))
                         (:depends-on node))))
          sorted-nodes))

(defn all-done? [nodes state-map]
  (every? #(= :done (get state-map (:id %))) nodes))

(defn paused? [state-map]
  (some #(= :paused %) (vals state-map)))

(defn splice-nodes [graph new-nodes]
  (update graph :graph/nodes into new-nodes))
