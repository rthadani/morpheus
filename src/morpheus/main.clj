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
   [clojure.tools.cli   :refer [parse-opts]]
   [morpheus.executor.engine  :as engine]
   [morpheus.executor.wiggum  :as wiggum]
   [morpheus.executor.store   :as store]
   [morpheus.slug             :as slug]
   [morpheus.system           :as sys]
   [taoensso.timbre      :as log])
  (:gen-class))

(def cli-options
  [["-p" "--project-dir PATH"        "Project directory for the agent"]
   [nil  "--agent AGENT"             "Agent: claude or pi (default: claude)"]
   [nil  "--step"                    "Step mode: pause after each iteration"]
   [nil  "--max-iterations N"       "Maximum iterations" :parse-fn parse-long]
   [nil  "--view"                    "View only: show saved UI state"]
   [nil  "--fresh"                   "Fresh run: remove cached work-dir"]
   [nil  "--polish"                  "Polish pass: back-fill WHY comments"]
   [nil  "--polish-only"             "Polish only: run standalone polish pass"]
   [nil  "--model MODEL"             "Model for both supervisor and executor (overrides EDN)"]
   [nil  "--supervisor-model MODEL"  "Model for supervisor (overrides EDN)"]
   [nil  "--executor-model MODEL"    "Model for executor (overrides EDN)"]
   ["-h" "--help"]])

(defn- parse-args [args]
  (let [{:keys [options arguments errors summary]}
        (parse-opts args cli-options)]
    (when errors
      (doseq [e errors]
        (println e))
      (println summary)
      (System/exit 1))
    (cond-> options
      (first arguments) (assoc :edn-file (first arguments)))))

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
    (println (str "\nPaused after iteration " (:iteration event)
                  (cond
                    (:review-pause? event) "  judge requested review"
                    (:verified? event)     "  verified"
                    :else                  "  not verified")))

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

    ;; Streaming agent activity. Wiggum emits :output-line (often token
    ;; deltas → stream inline); DAG emits :node-output-line (full activity
    ;; lines → one per line).
    :output-line
    (do (print (:line event)) (flush))

    :node-output-line
    (println (:line event))

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

(defn- parse-model-string
  "Parses a --model string according to the agent.

   :claude — splits on / to extract :provider and :model-id.
             e.g. 'kimi/kimi-k2.6' → {:provider :kimi :model-id 'kimi-k2.6'}
             e.g. 'claude-sonnet-4-7' → {:model-id 'claude-sonnet-4-7'}

   :pi     — passes the string through as :model-id (pi handles provider/id
             syntax internally via --model).
             e.g. 'google/gemini-3-flash-preview' → {:model-id 'google/gemini-3-flash-preview'}"
  [agent s]
  (when s
    (case (keyword agent)
      :pi
      {:agent    :pi
       :model-id s}

      :claude
      (if (str/includes? s "/")
        (let [[provider model-id] (str/split s #"/" 2)]
          {:agent    :claude
           :provider (keyword (str/lower-case provider))
           :model-id model-id})
        {:agent    :claude
         :model-id s}))))

(defn- apply-model-overrides
  [cfg {:keys [agent model supervisor-model executor-model]}]
  (let [agent (or agent "claude")
        [m s e]
        (map (partial parse-model-string agent)
             [model supervisor-model executor-model])]
    (cond-> cfg
      m (assoc :supervisor-model-config m
               :executor-model-config   m)
      s (assoc :supervisor-model-config s)
      e (assoc :executor-model-config e))))

(defn print-help
  [cmd]
  (println (str "Usage: " cmd " <graph.edn> [--options]"))
        (println "")
        (println "Options:")
            (println "  -p, --project-dir PATH        Project directory for Claude Code")
            (println "  -s, --step                    Step mode: pause after each iteration")
            (println "  -i, --max-iterations N        Maximum iterations")
            (println "  -v, --view                    View only: show saved UI state")
            (println "  -f, --fresh                   Fresh run: remove cached work-dir")
            (println "  -o, --polish                  Polish pass: back-fill WHY comments")
            (println "  -P, --polish-only              Polish only: run standalone polish pass")
            (println "      --agent AGENT             Agent: claude or pi (default: claude)")
            (println "      --model MODEL              Model for both supervisor and executor")
            (println "      --supervisor-model MODEL  Model for supervisor only")
            (println "      --executor-model MODEL    Model for executor only")
            (println "")
            (println "  Model format depends on --agent:")
            (println "    --agent claude → provider/model-id  e.g. kimi/kimi-k2.6, claude/claude-sonnet-4-7")
            (println "    --agent pi     → model pattern      e.g. google/gemini-3-flash-preview, kimi/kimi-k2.6")
            (println "  -h, --help                    Show this help")
        (println "")
        (println "Examples:")
        (println (str "  " cmd " graphs/examples/todo-app-wiggum.edn --project-dir /tmp/todo-react"))
        (println (str "  " cmd " graphs/examples/todo-app-dag.edn    --project-dir /tmp/todo-clj --step"))
        (println (str "  " cmd " --view --project-dir /tmp/todo-react"))
        (println (str "  " cmd " --polish-only --project-dir /tmp/todo-react")))

(defn -main [& args]
  (let [{:keys [edn-file project-dir step max-iterations
                view fresh polish polish-only help]
         :as   opts} (parse-args args)]

    (when help
      (let [cmd (invocation)]
        (print-help cmd))
      (System/exit 0))

    (when polish-only
      (when-not project-dir
        (println "--polish-only requires --project-dir")
        (System/exit 1))
      (run-polish-only! project-dir)
      (shutdown-agents)
      (System/exit 0))

    (when-not (or edn-file (and view project-dir))
      (let [cmd (invocation)]
       (print-help cmd))
      (System/exit 1))

    (when view
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
          rtype (detect-type raw)
          cfg   (apply-model-overrides raw opts)]

      ;; Stash the spec path so print-event's :run-error hint can echo a
      ;; ready-to-paste resume command.
      (System/setProperty "morpheus.spec-file" edn-file)

      (println (str "Loading " edn-file " [" (name rtype) "]"))

      (when (and fresh project-dir)
        (when-let [s (slug/project-slug project-dir)]
          (let [wd (str (System/getProperty "user.home") "/.morpheus/runs/" s)]
            (when (.exists (java.io.File. wd))
              (println (str "Removing cached work-dir: " wd))
              (shell/sh "rm" "-rf" wd)))))

      (sys/start!)
      (let [run-store (get-in @sys/system [:run-store])
            port      (or (some-> (System/getenv "PORT") parse-long) 7777)
            run-id    (or (case rtype
                            :wiggum (slug/project-slug project-dir)
                            :dag    (slug/graph-slug edn-file))
                          (slug/graph-slug edn-file)
                          (str "run_" (System/currentTimeMillis)))
            run       (case rtype
                        :wiggum
                        (wiggum/execute! run-id
                          (cond-> cfg
                            project-dir    (assoc :project-dir project-dir)
                            step           (assoc :step-once? true)
                            max-iterations (assoc :max-iterations max-iterations)
                            polish         (assoc :polish-pass? true)))

                        :dag
                        (engine/execute! run-id cfg
                          (cond-> {}
                            project-dir
                            (assoc :graph/params
                                   (assoc (:graph/params cfg {}) :project-dir project-dir)))))

            _         (store/add-run! run-store run)
            _         (println (str "UI: http://localhost:" port "/runs/" run-id))
            result    (event-loop! run rtype)
            work-dir  (when (= :wiggum rtype) @(:work-dir run))]

        (when (and (= :ok result) project-dir work-dir)
          (let [dest (-> project-dir java.io.File. .getAbsolutePath)]
            (.mkdirs (java.io.File. dest))
            ;; rsync excludes wiggum's internal snapshot repo + its own snapshot
            ;; file. Falls back to cp if rsync is unavailable.
            (let [{:keys [exit]} (shell/sh "rsync" "-a"
                                           "--exclude=.git"
                                           "--exclude=morpheus-run-snapshot.edn"
                                           (str work-dir "/")
                                           (str dest "/"))]
              (when-not (zero? exit)
                (shell/sh "sh" "-c"
                          (str "cp -r " work-dir "/. " dest "/ && "
                               "rm -rf " dest "/.git " dest "/morpheus-run-snapshot.edn")
                          :dir work-dir)))
            (println (str "Output copied to " dest))))

        (sys/stop!)
        (shutdown-agents)
        (System/exit (case result :ok 0 :aborted 1 2))))))
