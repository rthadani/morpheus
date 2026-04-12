(ns morpheus.main
  "CLI entry point. Loads an EDN file and runs it as a DAG or Wiggum run.

   Usage:
     clj -M:run <graph.edn> [--project-dir <path>] [--step] [--max-iterations <n>]

   Run type is detected automatically:
     EDN has :graph/nodes  → DAG executor  (engine/execute!)
     EDN has :objective    → Wiggum loop   (wiggum/execute!)

   Exit codes:
     0 — run completed successfully (verification passed or all nodes done)
     1 — run aborted or verification failed
     2 — unknown / timed out"
  (:require
   [clojure.edn         :as edn]
   [clojure.string      :as str]
   [clojure.core.async  :as async]
   [clojure.java.shell  :as shell]
   [morpheus.executor.engine  :as engine]
   [morpheus.executor.wiggum  :as wiggum]
   [morpheus.executor.store   :as store]
   [morpheus.system           :as sys])
  (:gen-class))

;; ──────────────────────────────────────────
;; Arg parsing
;; ──────────────────────────────────────────

(defn- parse-args
  "Returns a map of options from CLI args.
   Positional arg (no -- prefix) is treated as the EDN file path."
  [args]
  (loop [remaining (seq args) acc {}]
    (if (empty? remaining)
      acc
      (condp = (first remaining)
        "--project-dir"    (recur (drop 2 remaining)
                                  (assoc acc :project-dir (second remaining)))
        "--step"           (recur (rest remaining)
                                  (assoc acc :step-once? true))
        "--max-iterations" (recur (drop 2 remaining)
                                  (assoc acc :max-iterations
                                         (parse-long (second remaining))))
        (recur (rest remaining) (assoc acc :edn-file (first remaining)))))))

;; ──────────────────────────────────────────
;; Run type detection
;; ──────────────────────────────────────────

(defn- detect-type [cfg]
  (cond
    (contains? cfg :graph/nodes) :dag
    (contains? cfg :objective)   :wiggum
    :else (throw (ex-info
                   (str "Cannot detect run type.\n"
                        "  DAG graphs must have :graph/nodes\n"
                        "  Wiggum configs must have :objective")
                   {:keys (keys cfg)}))))

;; ──────────────────────────────────────────
;; Event printing
;; ──────────────────────────────────────────

(defn- print-event [event]
  (case (:type event)
    :run-started
    (println "▶  Run started")

    :iteration-started
    (do
      (when (= 1 (:iteration event))
        (println (str "   work-dir → " (:work-dir event))))
      (println (str "\n→  Iteration " (:iteration event) " starting…")))

    :iteration-complete
    (let [ev (:evidence event)]
      (println (str "   +" (count (:files-written ev)) " written"
                    "  ~" (count (:files-edited ev)) " edited"
                    "  exit=" (:exit-code ev)))
      (when-let [slop (:slop-signals ev)]
        (when (or (:helpers-added? slop) (:only-new-files? slop)
                  (> (:new-file-ratio slop 0) 70))
          (println (str "   ⚠  slop: new-ratio=" (:new-file-ratio slop) "%"
                        (when (:helpers-added? slop)  " helpers-added")
                        (when (:only-new-files? slop) " only-new-files")))))
      (when-let [v (:verification ev)]
        (println (str "   verify: "
                      (if (zero? (:exit v)) "✓ passed"
                          (str "✗ exit=" (:exit v)))))))

    :run-paused
    (println (str "\n⏸  Paused after iteration " (:iteration event)
                  (if (:verified? event) "  ✓ verified" "  ✗ not verified")))

    :provider-fallback
    (println (str "⚠  Rate limit — retrying with " (:fallback event)
                  " (after " (:delay-ms event) "ms)"))

    :node-complete
    (println (str "   ✓ " (name (:node-id event)) " (" (:duration event) "ms)"))

    :node-error
    (println (str "   ✗ " (name (:node-id event)) " — " (:message event)))

    :checkpoint
    (println (str "\n⏸  Checkpoint: " (name (:node-id event))))

    :run-complete
    (println (str "\n✅ Done"
                  (when-let [r (:reason event)]  (str " — " (name r)))
                  (when-let [i (:iteration event)] (str " (" i " iterations)"))))

    :run-aborted
    (println "\n✗  Run aborted")

    :run-error
    (println (str "\n💥 Run crashed — " (:message event)))

    ;; suppress noisy low-level events
    (:state-change :control-changed) nil

    nil))

;; ──────────────────────────────────────────
;; Interactive prompts
;; ──────────────────────────────────────────

(defn- prompt!
  "Prints msg, reads a line from stdin, returns it as a keyword."
  [msg]
  (print (str msg ": "))
  (flush)
  (keyword (str/trim (or (read-line) "abort"))))

;; ──────────────────────────────────────────
;; Blocking event loop
;; ──────────────────────────────────────────

(defn- event-loop!
  "Blocks the calling thread consuming events from a tap on (:event-mult run).
   Using a tap (not reading event-ch directly) so the SSE mult also gets every event.
   Returns :ok, :aborted, or :error."
  [run rtype]
  (let [tap-ch (async/chan 128)]
    (async/tap (:event-mult run) tap-ch)
    (try
      (loop []
        (if-let [event (async/<!! tap-ch)]
          (do
            (print-event event)
            (case (:type event)

              ;; DAG checkpoint — ask human to approve / revise / abort
              :checkpoint
              (let [action (prompt! "approve / revise / abort")]
                (engine/resume! run
                  {:action   action
                   :node-id  (:node-id event)
                   :feedback (when (= :revise action)
                               (do (print "Feedback: ") (flush) (read-line)))})
                (recur))

              ;; Wiggum pause — ask human to step / retry / abort
              :run-paused
              (let [action (prompt! "step / retry / abort")]
                (if (= :abort action)
                  (do (wiggum/resume! run {:action :abort}) :aborted)
                  (let [feedback (do (print "Feedback (optional, enter to skip): ")
                                     (flush)
                                     (let [fb (str/trim (or (read-line) ""))]
                                       (when (seq fb) fb)))]
                    (wiggum/resume! run {:action action :feedback feedback})
                    (recur))))

              ;; Terminal events — stop looping and return result
              :run-complete :ok
              :run-aborted  :aborted
              :run-error    :error

              ;; All other events — keep looping
              (recur)))

          ;; tap-ch closed (run channel closed) — treat as complete
          :ok))
      (finally
        (async/untap (:event-mult run) tap-ch)
        (async/close! tap-ch)))))

;; ──────────────────────────────────────────
;; Entry point
;; ──────────────────────────────────────────

(defn -main [& args]
  (let [{:keys [edn-file project-dir step-once? max-iterations]
         :as   opts} (parse-args args)]

    (when-not edn-file
      (println "Usage: clj -M:run <graph.edn> [--project-dir <path>] [--step] [--max-iterations <n>]")
      (println)
      (println "Examples:")
      (println "  clj -M:run graphs/examples/todo-app-wiggum.edn --project-dir /tmp/todo-react")
      (println "  clj -M:run graphs/examples/todo-app-dag.edn    --project-dir /tmp/todo-clj --step")
      (System/exit 1))

    (let [raw   (edn/read-string (slurp edn-file))
          rtype (detect-type raw)]

      (println (str "Loading " edn-file " [" (name rtype) "]"))

      ;; boot the HTTP server so the UI is reachable
      (sys/start!)
      (let [run-store (get-in @sys/system [:run-store])
            port      (or (some-> (System/getenv "PORT") parse-long) 7777)
            run-id    1
            run       (case rtype
                        :wiggum
                        (wiggum/execute! run-id
                          (cond-> raw
                            project-dir    (assoc :project-dir project-dir)
                            step-once?     (assoc :step-once? true)
                            max-iterations (assoc :max-iterations max-iterations)))

                        :dag
                        (engine/execute! run-id raw
                          (cond-> {}
                            project-dir
                            (assoc :graph/params
                                   (assoc (:graph/params raw {}) :project-dir project-dir)))))

            ;; register run so the UI can find it
            _         (store/add-run! run-store run)
            _         (println (str "UI → http://localhost:" port "/runs/" run-id))
            result    (event-loop! run rtype)
            work-dir  (when (= :wiggum rtype) @(:work-dir run))]

        ;; copy generated files back to project-dir
        (when (and (= :ok result) project-dir work-dir)
          (let [dest (-> project-dir java.io.File. .getAbsolutePath)]
            (.mkdirs (java.io.File. dest))
            (shell/sh "sh" "-c"
                      (str "cp -r " work-dir "/. " dest "/")
                      :dir work-dir)
            (println (str "Output copied → " dest))))

        (sys/stop!)
        (shutdown-agents)
        (System/exit (case result :ok 0 :aborted 1 2))))))
