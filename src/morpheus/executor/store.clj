(ns morpheus.executor.store
  "In-memory run store. Maps run-id → run map; serves both DAG and Wiggum runs.
   Swap the atom for a durable store in production."
  (:require
   [clojure.java.io    :as io]
   [clojure.edn        :as edn]
   [taoensso.timbre    :as log]
   [clojure.core.async :as async]
   [morpheus.slug      :as slug]))

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
  "Returns :wiggum for Wiggum runs, :dag for classic graph runs."
  [run]
  (if (contains? run :iterations) :wiggum :dag))

(defn- dag-summary [run]
  {:run-id     (:run-id run)
   :type       :dag
   :state      @(:state run)
   :nodes      (-> @(:graph-atom run) :graph/nodes)
   :state-map  @(:state run)
   :started-at (:started-at run)})

(defn- wiggum-summary [run]
  (let [events (when-let [log (:event-log run)] @log)]
    {:run-id           (:run-id run)
     :type             :wiggum
     :objective        (:objective run)
     :state            @(:state run)
     :iteration        (count @(:iterations run))
     :control-packet   @(:control-packet run)
     :evidence-list    @(:iterations run)
     :work-dir         @(:work-dir run)
     :started-at       (:started-at run)
     :last-pause-event (->> events (filter #(= :run-paused (:type %))) last)}))

(defn run-summary
  "Plain map for HTML rendering — no atoms. Works for DAG and Wiggum."
  [run]
  (case (run-type run)
    :wiggum (wiggum-summary run)
    :dag    (dag-summary run)))

(defn- cache-path
  "~/.morpheus/runs/{slug}/morpheus-ui-state.edn. Slug is the kebab-cased
   canonical basename of project-dir. Returns nil if project-dir is unset."
  [project-dir]
  (when-let [slug (slug/project-slug project-dir)]
    (str (System/getProperty "user.home") "/.morpheus/runs/" slug "/morpheus-ui-state.edn")))

(defn persist-run!
  "Saves a Wiggum run summary to ~/.morpheus/runs/{slug}/morpheus-ui-state.edn.
   No-op when :project-dir is absent."
  [run]
  (try
    (when-let [path (some-> (get-in run [:config :project-dir]) cache-path)]
      (let [summary (run-summary run)]
        (io/make-parents (io/file path))
        (spit path (pr-str summary))
        (log/info "UI state persisted" {:path path})))
    (catch Exception e
      (log/warn "Failed to persist UI state" {:message (ex-message e)}))))

(defn- frozen-wiggum-run
  "Reconstructs a read-only run from a persisted summary so the page renders
   after a server restart. SSE channel is closed immediately."
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
  "Loads ~/.morpheus/runs/{slug}/morpheus-ui-state.edn into the store so a
   previous run stays visible after restart. project-dir is used both to
   derive the slug (the file's cache path) and to override :run-id in the
   loaded summary — older snapshots stored numeric run-ids that no longer
   match URL routing."
  [store project-dir]
  (try
    (when-let [path (cache-path project-dir)]
      (let [f (io/file path)]
        (when (.exists f)
          (let [slug    (slug/project-slug project-dir)
                summary (-> (edn/read-string (slurp f))
                            (assoc :run-id slug))
                run     (frozen-wiggum-run summary)]
            (swap! store assoc (:run-id run) run)
            (log/info "Loaded persisted UI state" {:run-id (:run-id run) :path path})
            run))))
    (catch Exception e
      (log/warn "Failed to load UI state" {:project-dir project-dir :message (ex-message e)}))))

(defn event-log
  "Append-only event log for a Wiggum run. Nil for DAG runs."
  [run]
  (when-let [log-atom (:event-log run)]
    @log-atom))

(defn iterations
  "Vec of evidence maps for a Wiggum run, nil for DAG runs."
  [run]
  (when-let [itr-atom (:iterations run)]
    @itr-atom))
