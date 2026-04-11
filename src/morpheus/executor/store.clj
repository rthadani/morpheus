(ns morpheus.executor.store
  "In-memory run store. Maps run-id → run map.
   Supports both classic DAG runs (engine/execute!) and Wiggum runs (wiggum/execute!).
   In production swap the atom for a durable store.")

(defn create-store []
  (atom {}))

(defn add-run! [store run]
  (swap! store assoc (:run-id run) run)
  run)

(defn get-run [store run-id]
  (get @store run-id))

(defn all-runs [store]
  (vals @store))

(defn run-type
  "Returns :wiggum for Wiggum iteration runs, :dag for classic graph runs."
  [run]
  (if (contains? run :iterations) :wiggum :dag))

;; ──────────────────────────────────────────
;; Summaries — plain maps, no atoms
;; ──────────────────────────────────────────

(defn- dag-summary [run]
  {:run-id     (:run-id run)
   :type       :dag
   :state      @(:state run)
   :nodes      (-> @(:graph-atom run) :graph/nodes)
   :state-map  @(:state run)
   :started-at (:started-at run)})

(defn- wiggum-summary [run]
  {:run-id         (:run-id run)
   :type           :wiggum
   :objective      (:objective run)
   :state          @(:state run)
   :iteration      (count @(:iterations run))
   :control-packet @(:control-packet run)
   :evidence-list  @(:iterations run)
   :work-dir       @(:work-dir run)
   :started-at     (:started-at run)})

(defn run-summary
  "Returns a plain map suitable for HTML rendering — no atoms.
   Works for both DAG and Wiggum runs."
  [run]
  (case (run-type run)
    :wiggum (wiggum-summary run)
    :dag    (dag-summary run)))

;; ──────────────────────────────────────────
;; Event log access
;; ──────────────────────────────────────────

(defn event-log
  "Returns the full append-only event log for a Wiggum run.
   Returns nil for DAG runs (which use a channel, not a log atom)."
  [run]
  (when-let [log-atom (:event-log run)]
    @log-atom))

(defn iterations
  "Returns the vec of evidence maps for a Wiggum run, nil for DAG runs."
  [run]
  (when-let [itr-atom (:iterations run)]
    @itr-atom))
