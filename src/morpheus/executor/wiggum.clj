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
   [clojure.java.io             :as io]
   [clojure.java.shell          :as shell]
   [clojure.string              :as str]
   [taoensso.timbre             :as log]
   [morpheus.executor.claude-code  :as cc]
   [morpheus.executor.evidence     :as evidence]
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
        excludes  (mapcat #(vector "-not" "-path" (str "*/" % "/*")) ignores)
        args      (concat ["find" "." "-type" "f"] excludes)
        lines     (str/split-lines
                    (str/trim (:out (apply shell/sh (concat args [:dir work-dir])))))]
    (str/join "\n" (take 300 lines))))

(defn- verification-passed? [verification]
  (and (some? verification) (zero? (:exit verification))))

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
          (if (seq work-dir-contents)
            (str "> **Current top-level contents**: " work-dir-contents "\n"
                 "> All file paths must be relative to this directory."
                 " Do NOT prefix them with a project or directory name.\n\n")
            "> All source files are here. Do not look for a subdirectory matching the project name.\n\n")
          "# Objective\n\n"
          (:objective packet)
          (when (seq plan)
            (str "\n\n## This iteration\n\n"
                 (str/join "\n" (map-indexed #(str (inc %1) ". " %2) plan))))
          (when (seq constraints)
            (str "\n\n## Constraints\n\n"
                 (str/join "\n" (map #(str "- " %) constraints))))
          (when-let [sc (:success-check packet)]
            (str "\n\n## Done when\n\n"
                 "Running `" sc "` exits 0."))
          (when (seq anti-goals)
            (str "\n\n## Do not\n\n"
                 (str/join "\n" (map #(str "- " %) anti-goals))))
          "\n"))))

(defn- recent-evidence
  "Returns the last N evidence maps for the supervisor."
  [iterations-atom n]
  (vec (take-last n @iterations-atom)))

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
          expected-check   (when (seq (:expected-files control-packet))
                             (check-expected-files work-dir (:expected-files control-packet)))
          ev               (evidence/build iteration cc-result verification top-level tree expected-check)]
      (emit! run {:type      :iteration-complete
                  :iteration iteration
                  :evidence  ev})
      ev)))

;; ──────────────────────────────────────────
;; Main loop
;; ──────────────────────────────────────────

(defn execute!
  "Starts the Wiggum loop in a go-block. Returns the run map immediately.
   Sends events to (:event-ch run) as iterations proceed.
   Pauses after each iteration when step-once mode is enabled.

   run-config: see namespace docstring for keys."
  [run-id run-config]
  (let [run (create-run run-id run-config)]
    (go
      (set-state! run :running)
      (emit! run {:type :run-started :run-id run-id :objective (:objective run-config)})
      (try

      ;; create a persistent work dir for the whole run
      (let [work-dir (cc/make-work-dir! run-id "wiggum")]
        (reset! (:work-dir run) work-dir)

        ;; copy project into work dir once
        (when-let [pd (:project-dir run-config)]
          (log/info "Copying project into work dir" {:src pd :dst work-dir})
          (shell/sh "sh" "-c" (str "cp -r " pd "/* " work-dir "/") :dir work-dir))

        ;; snapshot the work-dir state after project copy — tells the supervisor
        ;; what was already present before any iteration ran.
        ;; Only top-level dirs/files: individual file paths would let the supervisor
        ;; hallucinate subdirectory names (e.g. the project-dir name) as path prefixes.
        (let [initial-state (let [tl (top-level-summary work-dir)]
                              (when (seq tl)
                                (str "Top-level contents of project root: " tl)))
              locked-check (:success-check run-config "echo ok")
              pin-check    #(assoc % :success-check locked-check)
              seed-packet  (supervisor/bootstrap run-config)
              first-packet (pin-check
                             (supervisor/review
                               (:objective run-config)
                               seed-packet
                               []
                               (or (:supervisor-model-config run-config)
                                   (:model-config run-config {}))
                               nil
                               initial-state))]
          (loop [iteration      1
                 control-packet first-packet]
          (reset! (:control-packet run) control-packet)

          (let [max-iter (or (:max-iterations run-config) 20)]
            (cond
              ;; hard cap reached
              (> iteration max-iter)
              (do
                (set-state! run :done)
                (emit! run {:type :run-complete :reason :max-iterations :iterations (dec iteration)}))

              ;; run aborted externally
              (= :aborted @(:state run))
              (emit! run {:type :run-aborted :run-id run-id})

              :else
              (let [ev (run-iteration! run work-dir iteration control-packet)
                    _  (swap! (:iterations run) conj ev)
                    verified?      (verification-passed? (:verification ev))
                    checkpoint-every (:checkpoint-every run-config)
                    milestone-hit?   (and checkpoint-every
                                          (zero? (mod iteration checkpoint-every)))]

                (if (or (:step-once? @(:control run)) milestone-hit?)

                  ;; ── pause for human review (always when step-once, even if verified) ──
                  (do
                    (set-state! run :paused)
                    (emit! run {:type       :run-paused
                                :iteration  iteration
                                :verified?  verified?
                                :milestone? milestone-hit?})
                    (let [action (<! (:resume-ch run))]
                      (set-state! run :running)
                      (case (:action action)
                        :abort
                        (do (set-state! run :aborted)
                            (emit! run {:type :run-aborted}))

                        :retry
                        (recur iteration control-packet)

                        :retry-with-overrides
                        (recur iteration (merge control-packet (:overrides action)))

                        ;; :step — if verified and human steps, we're done; otherwise supervisor
                        (if verified?
                          (do (set-state! run :done)
                              (when (:generate-claude-md? run-config)
                                (generate-project-claude-md! run work-dir))
                              (emit! run {:type :run-complete :reason :verified :iteration iteration}))
                          (let [feedback    (when (seq (:feedback action)) (:feedback action))
                                next-packet (pin-check
                                              (supervisor/review
                                                (:objective run-config)
                                                control-packet
                                                (recent-evidence (:iterations run) 3)
                                                (or (:supervisor-model-config run-config) (:model-config run-config {}))
                                                feedback
                                                initial-state))]
                            (recur (inc iteration) next-packet))))))

                  (if verified?
                    ;; auto-mode + verified — done
                    (do (set-state! run :done)
                        (when (:generate-claude-md? run-config)
                          (generate-project-claude-md! run work-dir))
                        (emit! run {:type :run-complete :reason :verified :iteration iteration}))

                    ;; ── auto-continue ──
                    (let [next-packet (pin-check
                                        (supervisor/review
                                          (:objective run-config)
                                          control-packet
                                          (recent-evidence (:iterations run) 3)
                                          (or (:supervisor-model-config run-config) (:model-config run-config {}))
                                          nil
                                          initial-state))]
                        (recur (inc iteration) next-packet)))))))))
        )
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
     :action   — :step | :retry | :retry-with-overrides | :abort
     :overrides — map merged into control packet (for :retry-with-overrides)"
  [run action-map]
  (put! (:resume-ch run) action-map))

(defn abort!
  "Signals the loop to stop after the current iteration."
  [run]
  (reset! (:state run) :aborted))

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
