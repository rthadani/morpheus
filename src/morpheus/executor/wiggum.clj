(ns morpheus.executor.wiggum
  "Iteration-based executor: run one bounded Claude Code pass, capture evidence,
   let the supervisor emit the next control packet, repeat until verification
   passes / max-iterations / aborted. Work directory persists across iterations.

   run-config keys:
     :objective      required  — product goal, stable across iterations
     :project-dir    optional  — copied into the work dir at start
     :success-check  optional  — shell command; exit 0 = done. Default \"echo ok\"
     :constraints    optional  — initial constraints for first control packet
     :anti-goals     optional  — initial anti-goals for first control packet
     :max-iterations optional  — hard cap (default 20)
     :step-once?     optional  — start in step-once mode
     :timeout-ms     optional  — per-iteration CC timeout (default 300000)
     :model-config            optional  — LLM for executor + supervisor
     :executor-model-config   optional  — overrides for executor only
     :supervisor-model-config optional  — overrides for supervisor only
     :generate-claude-md?     optional  — write a project CLAUDE.md after success
                                          (default true; pass false to skip)"
  (:require
   [clojure.core.async          :as async :refer [go chan put! <!]]
   [clojure.edn                 :as edn]
   [clojure.java.io             :as io]
   [clojure.java.shell          :as shell]
   [clojure.string              :as str]
   [taoensso.timbre             :as log]
   [morpheus.executor.claude-code  :as cc]
   [morpheus.executor.evidence     :as evidence]
   [morpheus.executor.judge        :as judge]
   [morpheus.executor.llm          :as llm]
   [morpheus.executor.store        :as store]
   [morpheus.executor.supervisor   :as supervisor]))

(defn create-run
  "Returns a fresh Wiggum run map. All mutable state in atoms."
  [run-id run-config]
  (let [event-ch (chan 64)]
    {:run-id         run-id
     :objective      (:objective run-config)
     :config         run-config
     :work-dir       (atom nil)
     :control-packet (atom nil)
     :iterations     (atom [])
     :initial-state  (atom nil)
     :steer-buffer   (atom nil)
     :live-output    (atom "")
     :event-log      (atom [])
     :state          (atom :pending)
     :control        (atom {:step-once? (boolean (:step-once? run-config false))})
     :event-ch       event-ch
     :event-mult     (async/mult event-ch)
     :resume-ch      (chan 1)
     :started-at     (System/currentTimeMillis)}))

(defn- emit!
  [{:keys [event-ch event-log]} event]
  (let [stamped (assoc event :ts (System/currentTimeMillis))]
    (swap! event-log conj stamped)
    (put! event-ch stamped)))

(defn- set-state! [run new-state]
  (reset! (:state run) new-state)
  (emit! run {:type :state-change :state new-state}))

(defn- run-verification! [work-dir command]
  (log/info "Running verification" {:command command})
  (let [result (shell/sh "sh" "-c" command :dir work-dir)]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

(defn- top-level-summary [work-dir]
  (let [f (io/file work-dir)]
    (->> (.listFiles f)
         (remove nil?)
         (remove #(= "CLAUDE.md" (.getName %)))
         (map #(str (.getName %) (if (.isDirectory %) "/" "")))
         sort
         (str/join "  "))))

(defn- check-expected-files [work-dir expected-files]
  (let [base (io/file work-dir)]
    (group-by (fn [path]
                (let [f (io/file base path)]
                  (if (str/ends-with? path "/")
                    (if (.isDirectory f) :present :missing)
                    (if (.exists f)      :present :missing))))
              expected-files)))

(defn- dir-tree
  "File listing for the supervisor, respecting .gitignore up to 2 levels deep
   and capped at 300 lines to keep prompts bounded."
  [work-dir]
  (let [gi-result (shell/sh "find" "." "-maxdepth" "2" "-name" ".gitignore"
                            :dir work-dir)
        gi-paths  (when (zero? (:exit gi-result))
                    (remove str/blank? (str/split-lines (:out gi-result))))
        ignores   (->> gi-paths
                       (mapcat (fn [rel-path]
                                 (let [f (io/file work-dir (str/replace rel-path #"^\.\/" ""))]
                                   (when (.exists f)
                                     (->> (str/split-lines (slurp f))
                                          (remove #(str/starts-with? % "#"))
                                          (remove #(str/starts-with? % "!"))
                                          (remove str/blank?)
                                          (map str/trim)
                                          (map #(str/replace % #"^/" ""))
                                          (map #(str/replace % #"/$" "")))))))
                       distinct)
        excludes  (concat ["-not" "-path" "*/.git/*"]
                          (mapcat #(vector "-not" "-path" (str "*/" % "/*")) ignores))
        args      (concat ["find" "." "-type" "f"] excludes)
        lines     (str/split-lines
                    (str/trim (:out (apply shell/sh (concat args [:dir work-dir])))))]
    (str/join "\n" (take 300 lines))))

(defn- verification-passed? [verification]
  (and (some? verification) (zero? (:exit verification))))

;; A lightweight git repo inside work-dir is used only for snapshot/restore
;; between phases — never pushed anywhere.

(defn- git-sh [work-dir & args]
  (apply shell/sh (concat (cons "git" args) [:dir work-dir])))

(defn- git-repo? [work-dir]
  (.exists (io/file work-dir ".git")))

(def ^:private snapshot-exclude
  (str/join "\n"
    ["CLAUDE.md"
     "morpheus-run-snapshot.edn"
     "morpheus-ui-state.edn"
     "node_modules/"
     ".next/"
     ".nuxt/"
     ".turbo/"
     ".vercel/"
     ".cache/"
     ".parcel-cache/"
     ".vite/"
     "coverage/"
     ".nyc_output/"
     "__pycache__/"
     "*.pyc"
     "*.pyo"
     ".venv/"
     "venv/"
     "env/"
     ".pytest_cache/"
     ".mypy_cache/"
     ".ruff_cache/"
     ".tox/"
     "*.egg-info/"
     "target/"
     ".cpcache/"
     ".clj-kondo/.cache/"
     ".lsp/.cache/"
     "*.class"
     "vendor/"
     "build/"
     "dist/"
     "out/"
     ".gradle/"
     "*.db"
     "*.db-journal"
     "*.sqlite"
     "*.sqlite3"
     "*.log"
     ".idea/"
     ".vscode/"
     ".DS_Store"
     "Thumbs.db"
     ""]))

(defn- ensure-git-repo! [work-dir]
  (when-not (git-repo? work-dir)
    (log/info "Initialising git snapshot repo" {:dir work-dir})
    (git-sh work-dir "init" "-q")
    (git-sh work-dir "config" "user.email" "morpheus@localhost")
    (git-sh work-dir "config" "user.name"  "morpheus")
    (let [excl (io/file work-dir ".git" "info" "exclude")]
      (io/make-parents excl)
      (spit excl snapshot-exclude))
    (git-sh work-dir "add" "-A")
    (git-sh work-dir "commit" "-q" "--allow-empty" "-m" "morpheus:baseline")))

(defn- git-diff [work-dir]
  ;; --intent-to-add so brand-new untracked files appear in the diff;
  ;; reset afterwards so a later commit isn't poisoned.
  (git-sh work-dir "add" "--intent-to-add" ".")
  (let [d (:out (git-sh work-dir "--no-pager" "diff" "HEAD"))]
    (git-sh work-dir "reset" "-q")
    d))

(defn- git-commit-phase! [work-dir iteration]
  (git-sh work-dir "add" "-A")
  (git-sh work-dir "commit" "-q" "--allow-empty" "-m"
          (str "morpheus:phase-end iter-" iteration)))

(defn- git-restore! [work-dir]
  (log/info "Restoring work-dir via git reset --hard" {:dir work-dir})
  (git-sh work-dir "reset" "--hard" "-q" "HEAD")
  (git-sh work-dir "clean" "-fdq"))

(def ^:private rate-limit-signals
  #{"rate_limit_error" "overloaded_error" "429" "too many requests" "rate limit"})

(defn- rate-limited? [{:keys [stdout stderr exit]}]
  (and (pos? (or exit 0))
       (let [out (str/lower-case (str stdout stderr))]
         (boolean (some #(str/includes? out %) rate-limit-signals)))))

(defn- run-with-fallback!
  "Retries a rate-limited CC run once with :fallback-model after :fallback-delay-ms.
   Fallback always runs vanilla Anthropic — :model-config is reset so a fallback
   id like claude-haiku-4-5-20251001 doesn't get sent to e.g. Moonshot."
  [run opts run-config]
  (let [result (cc/run! opts)]
    (if-not (rate-limited? result)
      result
      (if-let [fb-model (:fallback-model run-config)]
        (let [delay-ms (:fallback-delay-ms run-config 30000)]
          (log/warn "Rate limit detected — falling back" {:model fb-model :delay-ms delay-ms})
          (emit! run {:type     :provider-fallback
                      :reason   :rate-limit
                      :fallback fb-model
                      :delay-ms delay-ms})
          (Thread/sleep delay-ms)
          (cc/run! (assoc opts :model fb-model :model-config nil)))
        (do
          (log/warn "Rate limit detected but no :fallback-model configured")
          result)))))

(defn- strip-path-prefix [text prefix]
  (if (seq prefix)
    (str/replace text
                 (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote prefix) "/"))
                 "")
    text))

(defn- control-packet->claude-md
  ([packet] (control-packet->claude-md packet nil nil))
  ([packet work-dir-contents] (control-packet->claude-md packet work-dir-contents nil))
  ([packet work-dir-contents path-prefix-to-strip]
   (let [constraints (:constraints packet)
         anti-goals  (:anti-goals  packet)
         plan        (when (seq (:plan packet))
                       (map #(strip-path-prefix % path-prefix-to-strip) (:plan packet)))]
     (str "> **Working directory**: you are already in the project root.\n"
          "> Write ALL output files directly here using simple relative paths "
          "(e.g. `deps.edn`, `src/trader/llm.clj`).\n"
          "> Paths mentioned in the objective that look like `graphs/...`, "
          "`/home/...`, or any other directory are **read-only source references** "
          "— copy FROM them, never create directories or files AT those paths.\n"
          "> Never create a wrapper subdirectory; `deps.edn` lives at `./deps.edn`, not `graphs/foo/deps.edn`.\n"
          (if (seq work-dir-contents)
            (str "> **Current top-level contents**: " work-dir-contents "\n"
                 "> All files must be created DIRECTLY in this directory — not under a subdirectory that mirrors the project name.\n"
                 "> When scaffolding with npm/vite/etc, ALWAYS pass `.` as the project name to scaffold into the current directory.\n"
                 "> Example: `npm create vite@latest . -- --template react-ts` (NOT `npm create vite@latest my-app`)\n\n")
            (str "> All source files must be created DIRECTLY in this directory. Do not create a subdirectory matching the project name.\n"
                 "> When scaffolding with npm/vite/etc, ALWAYS pass `.` as the project name: e.g. `npm create vite@latest . -- --template react-ts`\n\n"))
          "# Objective\n\n"
          (:objective packet)
          (when (seq plan)
            (str "\n\n## This iteration\n\n"
                 (str/join "\n" (map-indexed #(str (inc %1) ". " %2) plan))))
          (when (seq constraints)
            (str "\n\n## Constraints\n\n"
                 (str/join "\n" (map #(str "- " %) constraints))))
          (when (seq (:expected-files packet))
            (str "\n\n## Stop when — this iteration only\n\n"
                 "Create **only** the following deliverables, then stop immediately.\n"
                 "Do not start any other files, phases, or features beyond this list:\n"
                 (str/join "\n" (map #(str "- " %) (:expected-files packet)))))
          (when-let [sc (:success-check packet)]
            (str "\n\n## Overall goal check (do not run this yourself)\n\n"
                 "The supervisor will run `" sc "` after you stop "
                 "to assess overall progress. Your job is only to deliver the files above."))
          (when (seq anti-goals)
            (str "\n\n## Do not\n\n"
                 (str/join "\n" (map #(str "- " %) anti-goals))))
          "\n"))))

(defn- recent-evidence [iterations-atom n]
  (vec (take-last n @iterations-atom)))

(defn- consume-steer! [run]
  (first (swap-vals! (:steer-buffer run) (constantly nil))))

(defn- snapshot-path [run-config work-dir]
  (if-let [pd (:project-dir run-config)]
    (str pd "/morpheus-run-snapshot.edn")
    (when work-dir
      (str work-dir "/morpheus-run-snapshot.edn"))))

(defn- write-snapshot! [run]
  (try
    (when-let [work-dir @(:work-dir run)]
      (when-let [path (snapshot-path (:config run) work-dir)]
        (let [snapshot {:run-id         (:run-id run)
                        :objective      (:objective run)
                        :config         (:config run)
                        :work-dir       work-dir
                        :iterations     @(:iterations run)
                        :control-packet @(:control-packet run)
                        :state          @(:state run)
                        :started-at     (:started-at run)
                        :initial-state  @(:initial-state run)}]
          (io/make-parents (io/file path))
          (spit path (pr-str snapshot))
          (log/info "Snapshot written" {:path path :iterations (count @(:iterations run))}))))
    (catch Exception e
      (log/warn "Snapshot write failed (run continues)" {:message (ex-message e)}))))

(defn read-snapshot [path]
  (edn/read-string (slurp path)))

(defn- load-snapshot
  "Snapshot map if one exists for run-config and its work-dir is still on disk."
  [run-config]
  (try
    (let [path (snapshot-path run-config nil)]
      (when (and path (.exists (io/file path)))
        (let [snap (read-snapshot path)]
          (if (.exists (io/file (:work-dir snap "")))
            snap
            (do (log/warn "Snapshot found but work-dir is gone — starting fresh" {:path path})
                nil)))))
    (catch Exception e
      (log/warn "Failed to read snapshot — starting fresh" {:message (ex-message e)})
      nil)))

(defn- generate-project-claude-md! [run work-dir]
  (let [config    (:config run)
        objective (:objective config)
        top       (top-level-summary work-dir)
        tree      (dir-tree work-dir)
        model-cfg (merge {:model-id (or (get-in config [:supervisor-model-config :model-id])
                                        "claude-haiku-4-5-20251001")}
                         (select-keys (or (:supervisor-model-config config) {})
                                      [:provider :base-url]))
        prompt    (str/join "\n\n"
                    ["You have just finished building a software project. Write a CLAUDE.md for it."
                     (str "## Original goal\n" objective)
                     (str "## Top-level structure\n" top)
                     (str "## Project files\n" tree)
                     (str "## What to include in CLAUDE.md\n"
                          "- What the project does (1-2 sentences)\n"
                          "- How to build, run, and test it (exact commands)\n"
                          "- Key architectural decisions and constraints\n"
                          "- Any agents, skills, or tools needed to maintain it\n\n"
                          "Write only the CLAUDE.md content. No preamble.")])
        content   (llm/complete model-cfg prompt)]
    (log/info "Writing project CLAUDE.md" {:work-dir work-dir :chars (count content)})
    (spit (str work-dir "/CLAUDE.md") content)))

(defn- run-iteration! [run work-dir iteration control-packet]
  (let [config        (:config run)
        timeout-ms    (:timeout-ms config 300000)
        exec-cfg      (or (:executor-model-config config)
                          (:model-config config))
        primary-model (:model-id exec-cfg)
        success-check (:success-check control-packet)
        current-top   (top-level-summary work-dir)
        ;; basename of project-dir, used to strip wrong path prefixes from plan
        ;; steps the supervisor produces (e.g. "kanban-full/src/" → "src/")
        proj-prefix   (some-> (get-in config [:project-dir])
                               io/file .getName)]
    (reset! (:live-output run) "")
    (cc/write-claude-md! work-dir (control-packet->claude-md control-packet current-top proj-prefix))
    (emit! run {:type           :iteration-started
                :iteration      iteration
                :work-dir       work-dir
                :control-packet control-packet})
    (let [cc-result    (run-with-fallback!
                         run
                         {:work-dir     work-dir
                          :prompt       (:objective control-packet)
                          :timeout-ms   timeout-ms
                          :model        primary-model
                          :model-config exec-cfg
                          :on-output    (fn [line]
                                          (swap! (:live-output run) str line "\n")
                                          (emit! run {:type      :output-line
                                                      :iteration iteration
                                                      :line      line}))}
                         config)
          verification     (when success-check
                             (run-verification! work-dir success-check))
          top-level        (top-level-summary work-dir)
          tree             (dir-tree work-dir)
          expected         (:expected-files control-packet)
          expected-check   (when (seq expected)
                             (check-expected-files work-dir expected))
          ;; A phase = the set of expected-files in the packet. The phase ends
          ;; the iteration in which they all exist (or immediately when none).
          phase-ended?     (or (empty? expected)
                               (empty? (:missing expected-check)))
          ev0              (evidence/build iteration cc-result verification top-level tree expected-check)
          review           (when (and phase-ended?
                                      (not (false? (:review? config))))
                             (judge/review!
                               (or (:supervisor-model-config config)
                                   (:model-config config {}))
                               {:objective      (:objective control-packet)
                                :expected-files expected
                                :constraints    (:constraints control-packet)
                                :anti-goals     (:anti-goals control-packet)
                                :files-written  (:files-written ev0)
                                :files-edited   (:files-edited ev0)
                                :files-deleted  (:files-deleted ev0)
                                :success-check  (:success-check control-packet)
                                :diff           (git-diff work-dir)}))
          ev               (assoc ev0 :review review :phase-ended? phase-ended?)]
      (emit! run {:type      :iteration-complete
                  :iteration iteration
                  :evidence  ev})
      ev)))

(defn- build-next-packet [run-config current-packet evidence-vec feedback initial-state]
  (assoc (supervisor/review
           (:objective run-config)
           current-packet
           evidence-vec
           (or (:supervisor-model-config run-config)
               (:model-config run-config {}))
           feedback
           initial-state)
         :success-check (:success-check run-config "echo ok")))

(defn- finish-run! [run run-config work-dir reason iteration]
  (set-state! run :done)
  (when (:generate-claude-md? run-config true)
    (generate-project-claude-md! run work-dir))
  (store/persist-run! run)
  (emit! run {:type :run-complete :reason reason :iteration iteration}))

(defn- init-run! [run run-config snapshot]
  (let [run-id    (:run-id run)
        resuming? (boolean snapshot)
        work-dir  (if resuming?
                    (do (log/info "Resuming from snapshot"
                                  {:work-dir       (:work-dir snapshot)
                                   :from-iteration (inc (count (:iterations snapshot)))})
                        (:work-dir snapshot))
                    (let [wd (cc/make-work-dir! run-id "wiggum")]
                      (when-let [pd (:project-dir run-config)]
                        (log/info "Copying project into work dir" {:src pd :dst wd})
                        (shell/sh "sh" "-c" (str "cp -r " pd "/* " wd "/") :dir wd))
                      wd))
        _             (reset! (:work-dir run) work-dir)
        _             (ensure-git-repo! work-dir)
        initial-state (if resuming?
                        (:initial-state snapshot)
                        (let [tl (top-level-summary work-dir)]
                          (when (seq tl)
                            (str "Top-level contents of project root: " tl))))
        _             (reset! (:initial-state run) initial-state)
        _             (when resuming?
                        (reset! (:iterations run)     (:iterations snapshot))
                        (reset! (:control-packet run) (:control-packet snapshot)))
        _             (emit! run {:type      (if resuming? :run-resumed :run-started)
                                  :run-id    run-id
                                  :objective (:objective run-config)})
        [start-iter start-packet]
        (if resuming?
          [(inc (count (:iterations snapshot))) (:control-packet snapshot)]
          (let [seed (supervisor/bootstrap run-config)]
            [1 (build-next-packet run-config seed [] nil initial-state)]))]
    {:work-dir      work-dir
     :initial-state initial-state
     :start-iter    start-iter
     :start-packet  start-packet}))

(defn- iteration-context [run run-config ev iteration]
  (let [verified?        (verification-passed? (:verification ev))
        checkpoint-every (:checkpoint-every run-config)
        milestone-hit?   (and checkpoint-every
                              (zero? (mod iteration checkpoint-every)))
        phase-ended?     (boolean (:phase-ended? ev))
        review           (:review ev)
        review-threshold (or (:review-threshold run-config) :high)
        review-pause?    (and phase-ended?
                              (judge/requires-pause? review review-threshold))]
    {:verified?     verified?
     :phase-ended?  phase-ended?
     :milestone?    milestone-hit?
     :review        review
     :review-pause? review-pause?
     :pause?        (or (:step-once? @(:control run)) milestone-hit? review-pause?)}))

(defn- merge-steer+feedback [feedback steer]
  (cond (and feedback steer) (str feedback "\n\n" steer)
        feedback             feedback
        steer                steer
        :else                nil))

(defn- next-packet-after-step [run run-config control-packet initial-state action]
  (build-next-packet run-config control-packet
                     (recent-evidence (:iterations run) 3)
                     (merge-steer+feedback
                       (when (seq (:feedback action)) (:feedback action))
                       (consume-steer! run))
                     initial-state))

(defn- apply-resume-action
  "Returns [:recur iter packet] or [:done] — only the loop can call recur in
   tail position, so we tag the next step and let the loop body dispatch."
  [action run run-config work-dir iteration control-packet initial-state ctx commit-phase!]
  (case (:action action)
    :abort
    (do (set-state! run :aborted)
        (emit! run {:type :run-aborted})
        [:done])

    ;; judge says this phase did damage; roll back and re-enter the phase
    :restore
    (do (git-restore! work-dir)
        [:recur iteration control-packet])

    :retry
    [:recur iteration control-packet]

    :retry-with-overrides
    [:recur iteration (merge control-packet (:overrides action))]

    (if (:verified? ctx)
      (do (commit-phase!)
          (finish-run! run run-config work-dir :verified iteration)
          [:done])
      (do (commit-phase!)
          [:recur (inc iteration)
                  (next-packet-after-step run run-config control-packet
                                          initial-state action)]))))

(defn- emit-pause!
  "Marks the run paused and announces it. The actual `<! resume-ch` happens
   inline in execute! — `<!` only works lexically inside (go ...)."
  [run iteration ctx]
  (set-state! run :paused)
  (emit! run (merge {:type :run-paused :iteration iteration}
                    (select-keys ctx [:verified? :milestone? :phase-ended?
                                      :review :review-pause?]))))

(defn execute!
  "Starts the Wiggum loop in a go-block. Returns the run map immediately.
   Sends events to (:event-ch run). Pauses after each iteration in step-once
   mode. Auto-resumes from project-dir/morpheus-run-snapshot.edn when the
   work-dir from that snapshot still exists."
  [run-id run-config]
  (let [run       (create-run run-id run-config)
        snapshot  (load-snapshot run-config)
        max-iters (or (:max-iterations run-config) 20)]
    (go
      (set-state! run :running)
      (try
        (let [{:keys [work-dir initial-state start-iter start-packet]}
              (init-run! run run-config snapshot)]
          (loop [iteration      start-iter
                 control-packet start-packet]
            (reset! (:control-packet run) control-packet)
            (cond
              (> iteration max-iters)
              (do (set-state! run :done)
                  (emit! run {:type :run-complete :reason :max-iterations
                              :iterations (dec iteration)}))

              (= :aborted @(:state run))
              (emit! run {:type :run-aborted :run-id run-id})

              :else
              (let [ev (run-iteration! run work-dir iteration control-packet)
                    _  (swap! (:iterations run) conj ev)
                    _  (write-snapshot! run)
                    _  (store/persist-run! run)
                    ctx (iteration-context run run-config ev iteration)
                    commit-phase! (fn [] (when (:phase-ended? ctx)
                                           (git-commit-phase! work-dir iteration)))]
                (cond
                  (:pause? ctx)
                  (do
                    (emit-pause! run iteration ctx)
                    (let [action  (<! (:resume-ch run))
                          _       (set-state! run :running)
                          outcome (apply-resume-action action run run-config work-dir
                                                       iteration control-packet
                                                       initial-state ctx commit-phase!)]
                      (case (first outcome)
                        :recur (recur (nth outcome 1) (nth outcome 2))
                        :done  nil)))

                  (:verified? ctx)
                  (do (commit-phase!)
                      (finish-run! run run-config work-dir :verified iteration))

                  :else
                  (do (commit-phase!)
                      (recur (inc iteration)
                             (build-next-packet run-config control-packet
                                                (recent-evidence (:iterations run) 3)
                                                (consume-steer! run)
                                                initial-state))))))))

        (catch Exception e
          (log/error e "Wiggum run crashed" {:run-id run-id :message (.getMessage e)})
          (set-state! run :error)
          (emit! run {:type :run-error :message (.getMessage e)}))))
    run))

(defn step!
  "Enable step-once mode — pause after the current iteration."
  [run]
  (swap! (:control run) assoc :step-once? true)
  (emit! run {:type :control-changed :step-once? true}))

(defn auto!
  "Disable step-once mode — resume auto-advancing."
  [run]
  (swap! (:control run) assoc :step-once? false)
  (emit! run {:type :control-changed :step-once? false}))

(defn resume!
  "Acts on a paused run. action-map keys:
     :action    — :step | :retry | :retry-with-overrides | :restore | :abort
     :overrides — map merged into control packet (for :retry-with-overrides)
     :feedback  — optional human steer text (for :step)
   :restore rewinds the work-dir to the previously-accepted git commit and
   re-enters the current phase, discarding what the executor just did."
  [run action-map]
  (put! (:resume-ch run) action-map))

(defn abort!
  "Stop the loop after the current iteration."
  [run]
  (reset! (:state run) :aborted))

(defn steer!
  "Queue human guidance for the next supervisor review. Overwrites any pending
   steer; pass nil/blank to clear."
  [run text]
  (let [t (when (seq text) text)]
    (reset! (:steer-buffer run) t)
    (emit! run {:type :steer-queued :text (or t "")})))

(defn clear-snapshot!
  "Delete the snapshot file for run-config so the next execute! starts fresh."
  [run-config]
  (when-let [path (snapshot-path run-config nil)]
    (let [f (io/file path)]
      (when (.exists f)
        (.delete f)
        (log/info "Snapshot deleted" {:path path})))))

(defn current-iteration [run]
  (count @(:iterations run)))

(defn last-evidence [run]
  (last @(:iterations run)))

(defn run-summary
  "Plain map for UI rendering — no atoms."
  [run]
  {:run-id         (:run-id run)
   :objective      (:objective run)
   :state          @(:state run)
   :iteration      (current-iteration run)
   :control-packet @(:control-packet run)
   :work-dir       @(:work-dir run)
   :started-at     (:started-at run)})
