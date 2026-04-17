(ns morpheus.executor.store
  "In-memory run store. Maps run-id → run map.
   Supports both classic DAG runs (engine/execute!) and Wiggum runs (wiggum/execute!).
   In production swap the atom for a durable store."
  (:require
   [clojure.java.io    :as io]
   [clojure.edn        :as edn]
   [taoensso.timbre    :as log]
   [clojure.core.async :as async]))

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
;; Persistence — project-dir/morpheus-ui-state.edn
;; ──────────────────────────────────────────

(defn persist-run!
  "Saves a completed Wiggum run's summary alongside the snapshot in project-dir.
   No-op when :project-dir is absent from config."
  [run]
  (try
    (when-let [pd (get-in run [:config :project-dir])]
      (let [summary (run-summary run)
            path    (str pd "/morpheus-ui-state.edn")]
        (io/make-parents (io/file path))
        (spit path (pr-str summary))
        (log/info "UI state persisted" {:path path})))
    (catch Exception e
      (log/warn "Failed to persist UI state" {:message (ex-message e)}))))

(defn- frozen-wiggum-run
  "Reconstructs a read-only run map from a persisted summary so the page
   renders correctly after a server restart. SSE channel is closed immediately."
  [summary]
  (let [noop-ch   (async/chan 1)
        noop-mult (async/mult noop-ch)]
    (async/close! noop-ch)
    {:run-id         (:run-id summary)
     :objective      (:objective summary)
     :state          (atom (:state summary))
     :iterations     (atom (:evidence-list summary))
     :control-packet (atom (:control-packet summary))
     :work-dir       (atom (:work-dir summary))
     :live-output    (atom "")
     :steer-buffer   (atom nil)
     :config         (:config summary)
     :started-at     (:started-at summary)
     :event-ch       noop-ch
     :event-mult     noop-mult
     :event-log      (atom [])}))

(defn load-ui-state!
  "Loads a persisted UI state from project-dir/morpheus-ui-state.edn into the store.
   Call this before starting a run to make the previous completed run visible."
  [store project-dir]
  (try
    (let [path (str project-dir "/morpheus-ui-state.edn")
          f    (io/file path)]
      (when (.exists f)
        (let [summary (edn/read-string (slurp f))
              run     (frozen-wiggum-run summary)]
          (swap! store assoc (:run-id run) run)
          (log/info "Loaded persisted UI state" {:run-id (:run-id run) :path path})
          run)))
    (catch Exception e
      (log/warn "Failed to load UI state" {:project-dir project-dir :message (ex-message e)}))))

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
