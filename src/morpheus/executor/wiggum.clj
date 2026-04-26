(ns morpheus.executor.wiggum
  "Wiggum loop — a single-threaded, iteration-based executor.

   Instead of walking a DAG of nodes, the Wiggum loop:
     1. Runs one bounded Claude Code iteration against the active control packet.
     2. Captures deterministic evidence (file changes, verification, slop signals).
     3. Pauses (if step-once mode) or calls the supervisor.
     4. The supervisor reviews evidence and emits a tighter control packet.
     5. Repeat until: verification passes, max-iterations reached, or aborted.

   The work directory persists across iterations so changes accumulate.
   The project is copied in once at run start — not on every iteration.

   run-config keys:
     :objective      required  — product goal, stable across all iterations
     :project-dir    optional  — path copied into the work dir at start
     :success-check  optional  — shell command; exit 0 = done. Default: \"echo ok\"
     :constraints    optional  — initial constraint list for first control packet
     :anti-goals     optional  — initial anti-goals for first control packet
     :max-iterations optional  — hard cap (default 20)
     :step-once?     optional  — start in step-once mode (default false)
     :timeout-ms     optional  — per-iteration CC timeout (default 300000)
     :model-config          optional  — LLM model for both executor and supervisor
     :executor-model-config optional  — overrides :model-config for the executor only
     :supervisor-model-config optional — overrides :model-config for the supervisor only
     :generate-claude-md? optional  — when true, generates a project-level CLAUDE.md
                                       after the run verifies successfully, overwriting
                                       the executor task-brief left in the work-dir"
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

;; ──────────────────────────────────────────
;; Run record
;; ──────────────────────────────────────────

(defn create-run
  "Returns a fresh Wiggum run map. All mutable state in atoms."
  [run-id run-config]
  (let [event-ch (chan 64)]
    {:run-id         run-id
     :objective      (:objective run-config)
     :config         run-config
     :work-dir       (atom nil)          ; set once the temp dir is created
     :control-packet (atom nil)          ; current control packet
     :iterations     (atom [])           ; vec of evidence maps (one per iteration)
     :initial-state  (atom nil)          ; top-level-summary before any iteration ran (persisted)
     :steer-buffer   (atom nil)          ; human guidance queued for next supervisor review
     :live-output    (atom "")          ; accumulated stdout for the running iteration
     :event-log      (atom [])           ; append-only log of all events
     :state          (atom :pending)     ; :pending :running :paused :done :aborted :error
     :control        (atom {:step-once? (boolean (:step-once? run-config false))})
     :event-ch       event-ch
     :event-mult     (async/mult event-ch) ; created once; SSE taps into this
     :resume-ch      (chan 1)
     :started-at     (System/currentTimeMillis)}))

;; ──────────────────────────────────────────
;; Internal helpers
;; ──────────────────────────────────────────

(defn- emit!
  [{:keys [event-ch event-log]} event]
  (let [stamped (assoc event :ts (System/currentTimeMillis))]
    (swap! event-log conj stamped)
    (put! event-ch stamped)))

(defn- set-state! [run new-state]
  (reset! (:state run) new-state)
  (emit! run {:type :state-change :state new-state}))

(defn- run-verification!
  "Runs the success-check shell command in work-dir.
   Returns {:exit n :output s}."
  [work-dir command]
  (log/info "Running verification" {:command command})
  (let [result (shell/sh "sh" "-c" command :dir work-dir)]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

(defn- top-level-summary
  "Returns a one-line summary of top-level entries in work-dir.
   e.g. \"kanban-app/ server/ Dockerfile docker-compose.yml\"
   Gives the supervisor an immediate picture of which phases exist."
  [work-dir]
  (let [f (io/file work-dir)]
    (->> (.listFiles f)
         (remove nil?)
         (remove #(= "CLAUDE.md" (.getName %)))
         (map #(str (.getName %) (if (.isDirectory %) "/" "")))
         sort
         (str/join "  "))))

(defn- check-expected-files
  "Checks which paths from the control packet's :expected-files exist in work-dir.
   Directories (trailing /) are checked with .isDirectory, files with .exists.
   Returns {:present [...] :missing [...]}."
  [work-dir expected-files]
  (let [base (io/file work-dir)]
    (group-by (fn [path]
                (let [f (io/file base path)]
                  (if (str/ends-with? path "/")
                    (if (.isDirectory f) :present :missing)
                    (if (.exists f)      :present :missing))))
              expected-files)))

(defn- dir-tree
  "Returns a file listing of work-dir for the supervisor.
   Searches for .gitignore files up to 2 levels deep (handles scaffolded subdirs
   like kanban-app/.gitignore). Caps output at 300 lines to keep prompts bounded."
  [work-dir]
  (let [;; find all .gitignore files within 2 levels
        gi-result (shell/sh "find" "." "-maxdepth" "2" "-name" ".gitignore"
                            :dir work-dir)
        gi-paths  (when (zero? (:exit gi-result))
                    (remove str/blank? (str/split-lines (:out gi-result))))
        ;; parse patterns from every .gitignore found
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

;; ──────────────────────────────────────────
;; Git snapshots — per-phase backup & restore
;;
;; A lightweight repo inside work-dir is used only for snapshotting. Baseline
;; commit captures the copied-in project state; each completed phase commits
;; on top of it. When the judge recommends a restore, we `git reset --hard`
;; back to the previous phase's commit and re-enter the phase.
;; ──────────────────────────────────────────

(defn- git-sh [work-dir & args]
  (apply shell/sh (concat (cons "git" args) [:dir work-dir])))

(defn- git-repo? [work-dir]
  (.exists (io/file work-dir ".git")))

(def ^:private snapshot-exclude
  "Additive .git/info/exclude content for the morpheus snapshot repo.
   Filters dependency trees, build outputs, caches, compiled bytecode,
   runtime DB/log files, IDE configs and OS files so the judge's diff
   stays focused on source the agent actually wrote."
  (str/join "\n"
    [;; morpheus bookkeeping
     "CLAUDE.md"
     "morpheus-run-snapshot.edn"
     "morpheus-ui-state.edn"
     ;; node / js
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
     ;; python
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
     ;; jvm / clojure
     "target/"
     ".cpcache/"
     ".clj-kondo/.cache/"
     ".lsp/.cache/"
     "*.class"
     ;; rust / go
     "vendor/"
     ;; generic build outputs
     "build/"
     "dist/"
     "out/"
     ".gradle/"
     ;; runtime data — never source
     "*.db"
     "*.db-journal"
     "*.sqlite"
     "*.sqlite3"
     "*.log"
     ;; editor / ide / os
     ".idea/"
     ".vscode/"
     ".DS_Store"
     "Thumbs.db"
     ""]))

(defn- ensure-git-repo!
  "Idempotently initialises a git repo in work-dir and makes a baseline commit.
   Writes a baseline .git/info/exclude (additive to any project .gitignore so
   the project's own ignore rules are left untouched) covering common heavy
   dirs, caches, build outputs and runtime artefacts. The snapshot repo lives
   only inside work-dir for diffing — it is never committed back."
  [work-dir]
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

(defn- git-diff
  "Raw diff of the working tree against the last commit. Uses `git add -N`
   (intent-to-add) so brand-new untracked files show up in the diff. Clears
   the intent-to-add afterwards so a later `git add -A && git commit` behaves
   normally."
  [work-dir]
  (git-sh work-dir "add" "--intent-to-add" ".")
  (let [d (:out (git-sh work-dir "--no-pager" "diff" "HEAD"))]
    (git-sh work-dir "reset" "-q")
    d))

(defn- git-commit-phase!
  "Commits the current phase's changes on top of the previous phase's commit."
  [work-dir iteration]
  (git-sh work-dir "add" "-A")
  (git-sh work-dir "commit" "-q" "--allow-empty" "-m"
          (str "morpheus:phase-end iter-" iteration)))

(defn- git-restore!
  "Resets work-dir to the last committed state and removes any untracked files
   the judged phase added."
  [work-dir]
  (log/info "Restoring work-dir via git reset --hard" {:dir work-dir})
  (git-sh work-dir "reset" "--hard" "-q" "HEAD")
  (git-sh work-dir "clean" "-fdq"))

;; ──────────────────────────────────────────
;; Provider fallback
;; ──────────────────────────────────────────

(def ^:private rate-limit-signals
  #{"rate_limit_error" "overloaded_error" "429" "too many requests" "rate limit"})

(defn- rate-limited? [{:keys [stdout stderr exit]}]
  (and (pos? (or exit 0))
       (let [out (str/lower-case (str stdout stderr))]
         (boolean (some #(str/includes? out %) rate-limit-signals)))))

(defn- run-with-fallback!
  "Runs CC with the given opts. If the result looks like a rate-limit error and
   run-config has a :fallback-model, waits :fallback-delay-ms (default 30s)
   then retries once with the fallback model.
   Emits :provider-fallback event if the retry fires."
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
          (cc/run! (assoc opts :model fb-model)))
        (do
          (log/warn "Rate limit detected but no :fallback-model configured")
          result)))))

(defn- strip-path-prefix
  "Removes occurrences of `prefix/` from text so plan steps like
   'create kanban-full/src/app.ts' become 'create src/app.ts'.
   No-op when prefix is nil or blank."
  [text prefix]
  (if (seq prefix)
    (str/replace text
                 (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote prefix) "/"))
                 "")
    text))

(defn- control-packet->claude-md
  "Generates a CLAUDE.md for the executor from the current control packet.

   work-dir-contents   — one-line string from top-level-summary, injected so the
                         executor sees exactly what exists before touching any files.
   path-prefix-to-strip — project dir basename (e.g. \"kanban-full\"); any plan step
                          that mentions this as a path prefix is rewritten in code
                          before the LLM ever sees it."
  ([packet] (control-packet->claude-md packet nil nil))
  ([packet work-dir-contents] (control-packet->claude-md packet work-dir-contents nil))
  ([packet work-dir-contents path-prefix-to-strip]
   (let [constraints (:constraints packet)
         anti-goals  (:anti-goals  packet)
         ;; strip wrong path prefix from plan steps *in code* — no LLM needed
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

(defn- recent-evidence
  "Returns the last N evidence maps for the supervisor."
  [iterations-atom n]
  (vec (take-last n @iterations-atom)))

(defn- consume-steer!
  "Atomically reads and clears the steer buffer. Returns the steer text or nil."
  [run]
  (first (swap-vals! (:steer-buffer run) (constantly nil))))

;; ──────────────────────────────────────────
;; Run snapshot — persist state after every iteration
;; ──────────────────────────────────────────

(defn- snapshot-path
  "Returns the snapshot file path, or nil when no stable path is available.
   Prefers project-dir so the snapshot survives temp-dir cleanup.
   Falls back to work-dir. Returns nil when both are absent (no-op for snapshots)."
  [run-config work-dir]
  (if-let [pd (:project-dir run-config)]
    (str pd "/morpheus-run-snapshot.edn")
    (when work-dir
      (str work-dir "/morpheus-run-snapshot.edn"))))

(defn- write-snapshot!
  "Serialises the run state after each completed iteration.
   Written to project-dir/morpheus-run-snapshot.edn (or work-dir as fallback)
   so execute! can resume automatically on the next invocation.
   Failures are logged as warnings and swallowed — the run must not crash due to I/O here."
  [run]
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

(defn read-snapshot
  "Reads the snapshot EDN from path and returns it as a map."
  [path]
  (edn/read-string (slurp path)))

(defn- load-snapshot
  "Returns the snapshot map if a valid snapshot exists for run-config
   and its work-dir is still present on disk, otherwise nil."
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

;; ──────────────────────────────────────────
;; Post-run CLAUDE.md generation
;; ──────────────────────────────────────────

(defn- generate-project-claude-md!
  "Called once after a successful run when :generate-claude-md? is true.
   Overwrites the executor task-brief CLAUDE.md with a real project CLAUDE.md
   generated by the supervisor LLM from the final state of the work-dir."
  [run work-dir]
  (let [config    (:config run)
        objective (:objective config)
        top       (top-level-summary work-dir)
        tree      (dir-tree work-dir)
        model-cfg (merge {:model-id    (or (get-in config [:supervisor-model-config :model-id])
                                           "claude-haiku-4-5-20251001")
                          :max-tokens  4096
                          :temperature 0.2}
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

;; ──────────────────────────────────────────
;; Core iteration
;; ──────────────────────────────────────────

(defn- run-iteration!
  "Runs one Claude Code iteration and returns evidence.
   Writes the control-packet CLAUDE.md, invokes CC (with fallback on rate limit),
   then runs the success-check if configured."
  [run work-dir iteration control-packet]
  (let [config        (:config run)
        timeout-ms    (:timeout-ms config 300000)
        primary-model (or (get-in config [:executor-model-config :model-id])
                          (get-in config [:model-config :model-id]))
        success-check (:success-check control-packet)
        ;; snapshot actual work-dir contents BEFORE running CC — injected into CLAUDE.md
        ;; so the executor sees exactly what exists and won't fabricate paths.
        current-top   (top-level-summary work-dir)
        ;; basename of the original project-dir, used to strip wrong path prefixes
        ;; from plan steps produced by the supervisor (e.g. "kanban-full/src/" → "src/")
        proj-prefix   (some-> (get-in config [:project-dir])
                               io/file .getName)]
    ;; write scoped CLAUDE.md for this iteration
    (reset! (:live-output run) "")
    (cc/write-claude-md! work-dir (control-packet->claude-md control-packet current-top proj-prefix))
    (emit! run {:type           :iteration-started
                :iteration      iteration
                :work-dir       work-dir
                :control-packet control-packet})
    ;; run Claude Code — no :project-dir (project copied at run start)
    (let [cc-result    (run-with-fallback!
                         run
                         {:work-dir   work-dir
                          :prompt     (:objective control-packet)
                          :timeout-ms timeout-ms
                          :model      primary-model
                          :on-output  (fn [line]
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
          ;; Phase boundary: a phase is the set of expected-files in the packet.
          ;; It ends the iteration in which all of those files are present (or
          ;; immediately, when the packet declares no expected-files at all).
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

;; ──────────────────────────────────────────
;; Execute! helpers
;; ──────────────────────────────────────────

(defn- build-next-packet
  "Calls the supervisor and pins :success-check from run-config onto the result."
  [run-config current-packet evidence-vec feedback initial-state]
  (assoc (supervisor/review
           (:objective run-config)
           current-packet
           evidence-vec
           (or (:supervisor-model-config run-config)
               (:model-config run-config {}))
           feedback
           initial-state)
         :success-check (:success-check run-config "echo ok")))

(defn- finish-run!
  "Marks run done, optionally generates a project CLAUDE.md, emits :run-complete."
  [run run-config work-dir reason iteration]
  (set-state! run :done)
  (when (:generate-claude-md? run-config)
    (generate-project-claude-md! run work-dir))
  (store/persist-run! run)
  (emit! run {:type :run-complete :reason reason :iteration iteration}))

(defn- init-run!
  "Sets up the work dir, initial-state snapshot, and first control packet.
   Mutates run atoms as a side effect.
   Returns {:work-dir :initial-state :start-iter :start-packet}."
  [run run-config snapshot]
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

;; ──────────────────────────────────────────
;; Main loop
;; ──────────────────────────────────────────

(defn execute!
  "Starts the Wiggum loop in a go-block. Returns the run map immediately.
   Sends events to (:event-ch run) as iterations proceed.
   Pauses after each iteration when step-once mode is enabled.

   If project-dir contains a morpheus-run-snapshot.edn from a previous run
   AND that run's work-dir still exists on disk, the loop resumes automatically
   from the last completed iteration rather than starting fresh.

   run-config: see namespace docstring for keys."
  [run-id run-config]
  (let [run      (create-run run-id run-config)
        snapshot (load-snapshot run-config)]
    (go
      (set-state! run :running)
      (try
        (let [{:keys [work-dir initial-state start-iter start-packet]}
              (init-run! run run-config snapshot)]
          (loop [iteration      start-iter
                 control-packet start-packet]
            (reset! (:control-packet run) control-packet)
            (cond
              (> iteration (or (:max-iterations run-config) 20))
              (do (set-state! run :done)
                  (emit! run {:type :run-complete :reason :max-iterations
                              :iterations (dec iteration)}))

              (= :aborted @(:state run))
              (emit! run {:type :run-aborted :run-id run-id})

              :else
              (let [ev               (run-iteration! run work-dir iteration control-packet)
                    _                (swap! (:iterations run) conj ev)
                    _                (write-snapshot! run)
                    _                (store/persist-run! run)
                    verified?        (verification-passed? (:verification ev))
                    checkpoint-every (:checkpoint-every run-config)
                    milestone-hit?   (and checkpoint-every
                                          (zero? (mod iteration checkpoint-every)))
                    phase-ended?     (boolean (:phase-ended? ev))
                    review           (:review ev)
                    review-threshold (or (:review-threshold run-config) :high)
                    review-pause?    (and phase-ended?
                                          (judge/requires-pause? review review-threshold))
                    commit-phase!    (fn [] (when phase-ended? (git-commit-phase! work-dir iteration)))]

                (if (or (:step-once? @(:control run)) milestone-hit? review-pause?)

                  ;; ── pause for human review ──────────────────
                  (do
                    (set-state! run :paused)
                    (emit! run {:type          :run-paused
                                :iteration     iteration
                                :verified?     verified?
                                :milestone?    milestone-hit?
                                :phase-ended?  phase-ended?
                                :review        review
                                :review-pause? review-pause?})
                    (let [action (<! (:resume-ch run))]
                      (set-state! run :running)
                      (case (:action action)
                        :abort
                        (do (set-state! run :aborted)
                            (emit! run {:type :run-aborted}))

                        ;; :restore — judge says this phase did damage; roll back
                        ;; to the previous accepted state and re-enter the phase.
                        :restore
                        (do (git-restore! work-dir)
                            (recur iteration control-packet))

                        :retry
                        (recur iteration control-packet)

                        :retry-with-overrides
                        (recur iteration (merge control-packet (:overrides action)))

                        ;; :step — verified = done; otherwise call supervisor
                        (if verified?
                          (do (commit-phase!)
                              (finish-run! run run-config work-dir :verified iteration))
                          (let [steer  (consume-steer! run)
                                fb     (when (seq (:feedback action)) (:feedback action))
                                merged (cond (and fb steer) (str fb "\n\n" steer)
                                             fb             fb
                                             steer          steer
                                             :else          nil)]
                            (commit-phase!)
                            (recur (inc iteration)
                                   (build-next-packet run-config control-packet
                                                      (recent-evidence (:iterations run) 3)
                                                      merged initial-state)))))))

                  ;; ── auto-continue ───────────────────────────
                  (if verified?
                    (do (commit-phase!)
                        (finish-run! run run-config work-dir :verified iteration))
                    (do (commit-phase!)
                        (recur (inc iteration)
                               (build-next-packet run-config control-packet
                                                  (recent-evidence (:iterations run) 3)
                                                  (consume-steer! run)
                                                  initial-state)))))))))

        (catch Exception e
          (log/error e "Wiggum run crashed" {:run-id run-id :message (.getMessage e)})
          (set-state! run :error)
          (emit! run {:type :run-error :message (.getMessage e)}))))
    run))

;; ──────────────────────────────────────────
;; External control
;; ──────────────────────────────────────────

(defn step!
  "Enable step-once mode. The loop pauses after the current iteration finishes."
  [run]
  (swap! (:control run) assoc :step-once? true)
  (emit! run {:type :control-changed :step-once? true}))

(defn auto!
  "Disable step-once mode. The loop resumes auto-advancing."
  [run]
  (swap! (:control run) assoc :step-once? false)
  (emit! run {:type :control-changed :step-once? false}))

(defn resume!
  "Called by the HTTP handler when a human acts on a paused run.

   action-map keys:
     :action   — :step | :retry | :retry-with-overrides | :restore | :abort
     :overrides — map merged into control packet (for :retry-with-overrides)

   :restore resets the work-dir to the previously-accepted git commit and
   re-enters the current phase, discarding whatever the executor just did."
  [run action-map]
  (put! (:resume-ch run) action-map))

(defn abort!
  "Signals the loop to stop after the current iteration."
  [run]
  (reset! (:state run) :aborted))

(defn steer!
  "Queues human guidance to be passed to the supervisor before the next iteration.
   Overwrites any previously queued steer. Pass nil or blank to clear."
  [run text]
  (let [t (when (seq text) text)]
    (reset! (:steer-buffer run) t)
    (emit! run {:type :steer-queued :text (or t "")})))

(defn clear-snapshot!
  "Deletes the snapshot file for run-config so the next execute! starts fresh.
   Useful when you want to discard a previous run's state and begin again."
  [run-config]
  (when-let [path (snapshot-path run-config nil)]
    (let [f (io/file path)]
      (when (.exists f)
        (.delete f)
        (log/info "Snapshot deleted" {:path path})))))

;; ──────────────────────────────────────────
;; Introspection helpers
;; ──────────────────────────────────────────

(defn current-iteration
  "Returns the index of the most recently completed iteration."
  [run]
  (count @(:iterations run)))

(defn last-evidence
  "Returns the evidence map for the most recently completed iteration."
  [run]
  (last @(:iterations run)))

(defn run-summary
  "Returns a plain map suitable for UI rendering — no atoms."
  [run]
  {:run-id         (:run-id run)
   :objective      (:objective run)
   :state          @(:state run)
   :iteration      (current-iteration run)
   :control-packet @(:control-packet run)
   :work-dir       @(:work-dir run)
   :started-at     (:started-at run)})
