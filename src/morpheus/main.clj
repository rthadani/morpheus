(ns morpheus.main
  "CLI entry point. Loads an EDN file and runs it as a DAG or Wiggum run.

   Usage:
     clj -M:run <graph.edn> [--project-dir <path>] [--step] [--max-iterations <n>]
     java -jar morpheus.jar <graph.edn> [--project-dir <path>] [--step] [--max-iterations <n>]

   Run type is auto-detected: :graph/nodes -> DAG, :objective -> Wiggum.

   Exit codes: 0 success, 1 aborted/failed, 2 unknown."
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

(defn- parse-args [args]
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
        "--view"           (recur (rest remaining)
                                  (assoc acc :view-only? true))
        "--fresh"          (recur (rest remaining)
                                  (assoc acc :fresh? true))
        "--polish"         (recur (rest remaining)
                                  (assoc acc :polish-pass? true))
        "--polish-only"    (recur (rest remaining)
                                  (assoc acc :polish-only? true))
        (recur (rest remaining) (assoc acc :edn-file (first remaining)))))))

(defn- detect-type [cfg]
  (cond
    (contains? cfg :graph/nodes) :dag
    (contains? cfg :objective)   :wiggum
    :else (throw (ex-info
                   (str "Cannot detect run type.\n"
                        "  DAG graphs must have :graph/nodes\n"
                        "  Wiggum configs must have :objective")
                   {:keys (keys cfg)}))))

(declare invocation)

(defn- print-event [event]
  (case (:type event)
    :run-started
    (println "Run started")

    :iteration-started
    (do
      (when (= 1 (:iteration event))
        (println (str "  work-dir: " (:work-dir event))))
      (println (str "\nIteration " (:iteration event) " starting")))

    :iteration-complete
    (let [ev (:evidence event)]
      (println (str "   +" (count (:files-written ev)) " written"
                    "  ~" (count (:files-edited ev)) " edited"
                    "  exit=" (:exit-code ev)
                    (when-let [m (:model ev)] (str "  model=" m))))
      (when-let [slop (:slop-signals ev)]
        (when (or (:helpers-added? slop) (:only-new-files? slop)
                  (> (:new-file-ratio slop 0) 70))
          (println (str "  slop: new-ratio=" (:new-file-ratio slop) "%"
                        (when (:helpers-added? slop)  " helpers-added")
                        (when (:only-new-files? slop) " only-new-files")))))
      (when-let [v (:verification ev)]
        (println (str "  verify: "
                      (if (zero? (:exit v)) "passed"
                          (str "fail exit=" (:exit v))))))
      (when-let [r (:review ev)]
        (println (str "  judge: score=" (:score r)
                      "  rec="  (name (:recommendation r))
                      (when-let [s (:summary r)] (str " - " s))))
        (doseq [v (:violations r)]
          (println (str "    [" (name (:severity v)) "] " (:file v)
                        " (" (name (:type v)) ") - " (:reason v))))))

    :run-paused
    (do
      (println (str "\nPaused after iteration " (:iteration event)
                    (cond
                      (:review-pause? event) "  judge requested review"
                      (:verified? event)     "  verified"
                      :else                  "  not verified")))
      (when-let [r (:review event)]
        (println (str "  judge: score=" (:score r)
                      "  rec="  (name (:recommendation r))
                      (when-let [s (:summary r)] (str " - " s))))
        (doseq [v (:violations r)]
          (println (str "    [" (name (:severity v)) "] " (:file v)
                        " (" (name (:type v)) ") - " (:reason v))))))

    :provider-fallback
    (println (str "Rate limit - retrying with " (:fallback event)
                  " (after " (:delay-ms event) "ms)"))

    :polish-started
    (println "\nPolish pass starting (back-fill WHY comments)")

    :polish-complete
    (println "  polish done")

    :polish-skipped
    (println (str "  polish skipped — " (name (:reason event))
                  (when-let [m (:message event)] (str ": " m))))

    :node-complete
    (println (str "  done " (name (:node-id event)) " (" (:duration event) "ms)"))

    :node-error
    (println (str "  fail " (name (:node-id event)) " - " (:message event)))

    :checkpoint
    (println (str "\nCheckpoint: " (name (:node-id event))))

    :run-complete
    (println (str "\nDone"
                  (when-let [r (:reason event)]  (str " - " (name r)))
                  (when-let [i (:iteration event)] (str " (" i " iterations)"))))

    :run-aborted
    (println "\nRun aborted")

    :run-error
    (do
      (println (str "\nRun crashed at iter " (:iteration event)
                    " - " (:message event)))
      (when (:continuable? event)
        (let [cmd     (invocation)
              edn-arg (or (System/getProperty "morpheus.spec-file") "<spec.edn>")
              proj    (or (:project-dir event) "<project-dir>")]
          (println "")
          (println "Run is continuable — snapshot is intact. Resume with:")
          (println (str "  " cmd " " edn-arg " --project-dir " proj)))))

    (:state-change :control-changed) nil

    nil))

(defn- prompt! [msg]
  (print (str msg ": "))
  (flush)
  (keyword (str/trim (or (read-line) "abort"))))

(defn- event-loop!
  "Blocks consuming events from a tap on (:event-mult run). Using a tap (not
   event-ch directly) so the SSE mult sees every event too."
  [run rtype]
  (let [tap-ch (async/chan 128)]
    (async/tap (:event-mult run) tap-ch)
    (try
      (loop []
        (if-let [event (async/<!! tap-ch)]
          (do
            (print-event event)
            (case (:type event)

              :checkpoint
              (let [action (prompt! "approve / revise / abort")]
                (engine/resume! run
                  {:action   action
                   :node-id  (:node-id event)
                   :feedback (when (= :revise action)
                               (do (print "Feedback: ") (flush) (read-line)))})
                (recur))

              ;; Wiggum pause is driven by the web UI dialog; the CLI just
              ;; keeps consuming events.
              :run-paused
              (do (println "  respond in the UI to continue or restore")
                  (recur))

              :run-complete :ok
              :run-aborted  :aborted
              :run-error    :error

              (recur)))

          :ok))
      (finally
        (async/untap (:event-mult run) tap-ch)
        (async/close! tap-ch)))))

(defn- invocation
  "Best-effort detection of how the user launched the process, so the usage
   message matches what they typed. Falls back to `clj -M:run`."
  []
  (let [cmd (or (System/getProperty "sun.java.command") "")]
    (if (re-find #"\.jar(\s|$)" cmd)
      (str "java -jar " (or (re-find #"\S+\.jar" cmd) "morpheus.jar"))
      "clj -M:run")))

(defn- run-polish-only!
  "Standalone polish: load the snapshot for project-dir and run one polish
   pass against the saved work-dir. Prints events as they arrive and exits."
  [project-dir]
  (if-let [snap (wiggum/find-snapshot project-dir)]
    (let [work-dir (:work-dir snap)
          event-ch (async/chan 256)
          mult     (async/mult event-ch)
          tap-ch   (async/chan 256)
          run      {:config       (:config snap)
                    :run-id       (:run-id snap)
                    :live-output  (atom "")
                    :event-ch     event-ch
                    :event-mult   mult
                    :event-log    (atom [])}
          printer  (future
                     (loop []
                       (when-let [ev (async/<!! tap-ch)]
                         (print-event ev)
                         (recur))))]
      (println (str "Polishing " work-dir))
      (async/tap mult tap-ch)
      (try
        (wiggum/run-polish-pass! run work-dir
                                 (count (:iterations snap [])))
        (finally
          (async/close! event-ch)
          @printer)))
    (do (println (str "No snapshot found for " project-dir))
        (System/exit 1))))

(defn -main [& args]
  (let [{:keys [edn-file project-dir step-once? max-iterations
                view-only? fresh? polish-pass? polish-only?]
         :as   opts} (parse-args args)]

    (when polish-only?
      (when-not project-dir
        (println "--polish-only requires --project-dir")
        (System/exit 1))
      (run-polish-only! project-dir)
      (shutdown-agents)
      (System/exit 0))

    (when-not (or edn-file (and view-only? project-dir))
      (let [cmd (invocation)]
        (println (str "Usage: " cmd " <graph.edn> [--project-dir <path>] [--step] [--max-iterations <n>] [--fresh] [--polish]"))
        (println (str "       " cmd " --view --project-dir <path>"))
        (println (str "       " cmd " --polish-only --project-dir <path>"))
        (println)
        (println "Examples:")
        (println (str "  " cmd " graphs/examples/todo-app-wiggum.edn --project-dir /tmp/todo-react"))
        (println (str "  " cmd " graphs/examples/todo-app-dag.edn    --project-dir /tmp/todo-clj --step"))
        (println (str "  " cmd " --view --project-dir /tmp/todo-react"))
        (println (str "  " cmd " --polish-only --project-dir /tmp/todo-react")))
      (System/exit 1))

    (when view-only?
      (sys/start!)
      (let [run-store (get-in @sys/system [:run-store])
            port      (or (some-> (System/getenv "PORT") parse-long) 7777)
            run       (store/load-ui-state! run-store project-dir)]
        (if run
          (do (println (str "UI: http://localhost:" port "/runs/" (:run-id run)))
              (println "Press Ctrl-C to stop.")
              (.addShutdownHook (Runtime/getRuntime) (Thread. #(sys/stop!)))
              @(promise))
          (do (println (str "No saved UI state found in " project-dir))
              (sys/stop!)
              (System/exit 1)))))

    (let [raw   (edn/read-string (slurp edn-file))
          rtype (detect-type raw)]

      ;; Stash the spec path so print-event's :run-error hint can echo a
      ;; ready-to-paste resume command.
      (System/setProperty "morpheus.spec-file" edn-file)

      (println (str "Loading " edn-file " [" (name rtype) "]"))

      (when (and fresh? project-dir)
        (let [home (System/getProperty "user.home")
              slug (-> project-dir java.io.File. .getCanonicalFile .getName)
              wd   (str home "/.morpheus/runs/" slug)]
          (when (.exists (java.io.File. wd))
            (println (str "Removing cached work-dir: " wd))
            (shell/sh "rm" "-rf" wd))))

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
                            max-iterations (assoc :max-iterations max-iterations)
                            polish-pass?   (assoc :polish-pass? true)))

                        :dag
                        (engine/execute! run-id raw
                          (cond-> {}
                            project-dir
                            (assoc :graph/params
                                   (assoc (:graph/params raw {}) :project-dir project-dir)))))

            _         (store/add-run! run-store run)
            _         (println (str "UI: http://localhost:" port "/runs/" run-id))
            result    (event-loop! run rtype)
            work-dir  (when (= :wiggum rtype) @(:work-dir run))]

        (when (and (= :ok result) project-dir work-dir)
          (let [dest (-> project-dir java.io.File. .getAbsolutePath)]
            (.mkdirs (java.io.File. dest))
            (shell/sh "sh" "-c"
                      (str "cp -r " work-dir "/. " dest "/")
                      :dir work-dir)
            (println (str "Output copied to " dest))))

        (sys/stop!)
        (shutdown-agents)
        (System/exit (case result :ok 0 :aborted 1 2))))))
