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
                                          (default true; pass false to skip)
     :polish-pass?            optional  — when true, run one extra CC pass after
                                          verification to back-fill brief WHY
                                          comments on non-obvious code. Best-
                                          effort; quota / errors are logged and
                                          the run finalises regardless.
                                          (default false)"
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
   [morpheus.executor.supervisor   :as supervisor]
   [morpheus.graph.context         :as graph-ctx]))

(defn- render-run-config
  "Substitutes {{slot}} placeholders in :objective and :acceptance-criteria
   using other top-level keys of the run-config as the slot values. Lets a
   spec author hoist a :description (or any other) key out of the long
   :objective string and reference it via {{description}}."
  [run-config]
  (let [slot-vals (into {} (filter (comp string? val) run-config))]
    (cond-> run-config
      (string? (:objective run-config))
      (update :objective graph-ctx/render-prompt slot-vals)
      (string? (:acceptance-criteria run-config))
      (update :acceptance-criteria graph-ctx/render-prompt slot-vals))))

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
     :max-iters      (atom (or (:max-iterations run-config) 20))
     :control        (atom {:step-once? (boolean (:step-once? run-config false))})
     :event-ch       event-ch
     :event-mult     (async/mult event-ch)
     :resume-ch      (chan 1)
     :started-at     (System/currentTimeMillis)}))

(defn emit!
  "Append-and-publish: stamps the event with :ts, conjs onto :event-log, and
   puts it on :event-ch. Public so retry-on-exhaustion can expand to call it."
  [{:keys [event-ch event-log]} event]
  (let [stamped (assoc event :ts (System/currentTimeMillis))]
    (swap! event-log conj stamped)
    (put! event-ch stamped)))

(defn set-state!
  "Resets the run's state atom and emits a :state-change event. Public so
   retry-on-exhaustion can expand to call it."
  [run new-state]
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

(defn- rate-limited?
  "Truthy if the result text contains a quota / rate-limit signal. Accepts
   either a cc/run! result map ({:stdout :stderr :exit}) or a plain string.
   Signals are sourced from morpheus.executor.llm to keep one source of truth."
  [x]
  (let [text (cond
               (string? x) x
               (map? x)    (str (:stdout x) (:stderr x) (:output x))
               :else       (str x))
        ;; For maps, only count it as rate-limited if exit was non-zero —
        ;; otherwise the run actually succeeded and the text is incidental.
        non-zero? (or (string? x)
                      (pos? (or (:exit x) (:exit-code x) 0)))]
    (and non-zero? (llm/rate-limited? text))))

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
  ([packet] (control-packet->claude-md packet nil nil :code))
  ([packet work-dir-contents] (control-packet->claude-md packet work-dir-contents nil :code))
  ([packet work-dir-contents path-prefix-to-strip]
   (control-packet->claude-md packet work-dir-contents path-prefix-to-strip :code))
  ([packet work-dir-contents path-prefix-to-strip mode]
   (let [mode        (or mode :code)
         research?   (= mode :research)
         constraints (:constraints packet)
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
          (if research?
            (str "> **Output style — research deliverable.** This iteration produces written analysis, not source code. Substance matters more than brevity, but fluff still wastes tokens.\n"
                 "> - Cite every load-bearing claim. Inline URLs for web sources; file paths plus line/section ranges for local sources; ticker + filing type + date for SEC documents. A claim with no source is one the next iteration cannot verify.\n"
                 "> - Distinguish observed facts from inferences. Phrase inferences as inferences (\"this suggests…\", \"likely because…\"), not as facts.\n"
                 "> - Include counter-evidence and alternatives considered. A one-sided writeup with no dissent is a reviewer red flag.\n"
                 "> - Prefer concrete numbers and examples over abstract framing — \"revenue grew 18% YoY\" beats \"revenue grew significantly\".\n"
                 "> - Don't pad to look thorough. Skip transition paragraphs, executive-summary restatements, and \"in conclusion\" wrap-ups. End where the analysis ends.\n"
                 "> - In your own chat output: skip the preamble (\"I'll first fetch the filings…\") and the trailing summary (\"I've written the report — here's what it covers\"). The deliverable is the file; chat is overhead.\n\n"
                 "> **Prose shape — research deliverable.**\n"
                 "> - One document per deliverable. Don't split a 400-line briefing into four 100-line files for tidiness.\n"
                 "> - Headings and bullet lists are fine; tables when comparing entities. Don't invent novel document structures — use the conventions of the genre (briefing, due-diligence memo, literature review).\n"
                 "> - No code in prose deliverables unless the objective explicitly asks for it (e.g. a methodology appendix).\n\n")
            (str "> **Output style — save tokens.** The user's quota covers every comment, docstring, and chat line you emit. Documentation is added out-of-band after the run finishes, so do not produce any here.\n"
                 "> - NO comments. NO docstrings. None on public APIs, none on private helpers, none on tests.\n"
                 "> - The ONLY exception is a comment the language or tooling reads as a directive — `# type: ignore`, `// @ts-ignore`, `// eslint-disable-next-line`, `;; clj-kondo: ...`, build-tool pragmas, etc. Include those only when functionally required for the code to type-check, lint, or run. Pure documentation directives like JSDoc / docstring blocks do NOT qualify.\n"
                 "> - Don't restate what the code does, don't write `// added for X`, `# TODO`, `;; removed Y`, decorative banners, or comments referencing the current task.\n"
                 "> - In your own chat output: skip the preamble (\"I'll first read the files…\") and the trailing summary (\"I've created X — here's what it does\"). Just do the work and stop.\n\n"
                 "> **Code shape — keep it minimal.** Do the simplest thing that satisfies *this iteration's* objective. Don't generalise for needs not on the plan.\n"
                 "> - Plain functions on plain data first (maps, vectors, records). Do NOT introduce protocols, defrecords, multimethods, interfaces, abstract classes, factories, registries, or \"manager\"/\"service\"/\"handler\" wrapper layers unless the objective explicitly requires runtime polymorphism that functions cannot express. A possible second implementation later is not a reason to add one now.\n"
                 "> - Avoid **orphan** global state. The test isn't \"atom vs no atom\" — it's \"who owns this state's lifecycle?\". State held inside a component/system map (integrant, mount), a connection pool, a bounded cache with eviction, channel mults, or memoization is fine — those have owners that dispose of them. Orphans like top-level `(defonce app-state (atom {}))` used as kitchen-sink storage, atoms standing in for a database, or globals introduced just because \"state has to live somewhere\" are NOT fine — pass state through args or scope it to a component. Same lifecycle test applies to Python module-level dicts and JS module-load singletons.\n"
                 "> - For full-stack apps, keep route/handler/controller functions thin: parse + validate input, delegate to a domain function in a separate namespace/module, format the response. No DB queries, business logic, or template rendering inline in the route body. Routes are wiring; logic lives in pure functions next door.\n"
                 "> - Function size — Goldilocks: not pages, not one-liners. Extract a helper ONLY when it (a) does a cohesive step the parent function shouldn't have to think about, OR (b) is called from 2+ places. Do NOT extract one-line wrappers that just rename, forward args, or expose a field of a map — e.g. `(defn user-id [u] (:id u))` or `def get_user_name(u): return u['name']` is noise, write the access inline. On the long side, extract when a function exceeds one screen, has ~6+ `let` bindings, 3+ levels of nested branching, or does two distinct steps in one body.\n"
                 "> - No defensive try/catch around code you control. Validate only at trust boundaries (user input, network, disk).\n"
                 "> - No new dependencies unless the objective names one. Stdlib first.\n"
                 "> - Inline before you abstract. Three obvious lines beat a clever one-line abstraction. If two functions look similar, leave them — don't refactor to dedupe unless the objective asks.\n"
                 "> - One namespace / module per concern. Don't split a 40-line file into four 10-line files for tidiness.\n\n"))
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

(defn- stable-work-dir
  "Stable cache path under ~/.morpheus/runs/{slug}/ derived from project-dir.
   Returns nil when no project-dir is set (ephemeral tempdir is used in that case)."
  [run-config]
  (when-let [pd (:project-dir run-config)]
    (let [home (System/getProperty "user.home")
          slug (-> pd io/file .getCanonicalFile .getName)]
      (str home "/.morpheus/runs/" slug))))

(defn- work-dir-empty? [path]
  (let [f (io/file path)]
    (or (not (.exists f))
        (zero? (count (.listFiles f))))))

(defn- snapshot-path
  "Snapshot file lives inside the work-dir so it is co-located with the state
   it describes and never pollutes the user's project-dir."
  [_run-config work-dir]
  (when work-dir
    (str work-dir "/morpheus-run-snapshot.edn")))

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
  "Snapshot map if one exists at the stable work-dir for this run-config.
   Looks under ~/.morpheus/runs/{slug}/morpheus-run-snapshot.edn. Returns nil
   if the snapshot is missing, malformed, or its work-dir is gone."
  [run-config]
  (try
    (when-let [wd (stable-work-dir run-config)]
      (let [path (snapshot-path run-config wd)]
        (when (and path (.exists (io/file path)))
          (let [snap (read-snapshot path)]
            (if (.exists (io/file (:work-dir snap "")))
              snap
              (do (log/warn "Snapshot found but work-dir is gone — starting fresh" {:path path})
                  nil))))))
    (catch Exception e
      (log/warn "Failed to read snapshot — starting fresh" {:message (ex-message e)})
      nil)))

(defn find-snapshot
  "Public lookup: returns the snapshot map at ~/.morpheus/runs/{slug}/
   morpheus-run-snapshot.edn for a given project-dir, or nil if none exists
   or its work-dir has gone away. Used by --polish-only to find the work-dir
   to operate on without rerunning the engine."
  [project-dir]
  (try
    (when project-dir
      (let [home (System/getProperty "user.home")
            slug (-> project-dir io/file .getCanonicalFile .getName)
            path (str home "/.morpheus/runs/" slug "/morpheus-run-snapshot.edn")
            f    (io/file path)]
        (when (.exists f)
          (let [snap (read-snapshot path)]
            (when (.exists (io/file (:work-dir snap "")))
              snap)))))
    (catch Exception e
      (log/warn "Failed to read snapshot"
                {:project-dir project-dir :message (ex-message e)})
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
                               io/file .getName)
        judge-mode    (or (:judge-mode config) :code)]
    (reset! (:live-output run) "")
    (cc/write-claude-md! work-dir (control-packet->claude-md control-packet current-top proj-prefix judge-mode))
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
          review-disabled? (or (= :none (:review-threshold config))
                               (false? (:review? config)))   ; legacy alias
          ;; Skip the judge for rate-limited iterations — the output is an
          ;; error message, not real work, and the judge has nothing useful
          ;; to say about it.
          rl?              (rate-limited? cc-result)
          review           (when (and phase-ended?
                                      (not review-disabled?)
                                      (not rl?))
                             (judge/review!
                               (or (:supervisor-model-config config)
                                   (:model-config config {}))
                               {:mode           (or (:judge-mode config) :code)
                                :objective      (:objective control-packet)
                                :expected-files expected
                                :expected-check expected-check
                                :constraints    (:constraints control-packet)
                                :anti-goals     (:anti-goals control-packet)
                                :files-written  (:files-written ev0)
                                :files-edited   (:files-edited ev0)
                                :files-deleted  (:files-deleted ev0)
                                :success-check  (:success-check control-packet)
                                :diff           (git-diff work-dir)}))
          ev               (assoc ev0
                                   :review review
                                   :phase-ended? phase-ended?
                                   :exhausted? rl?
                                   :quota-message (when rl?
                                                    (llm/exhaustion-message
                                                      (str (:stdout cc-result)
                                                           "\n"
                                                           (:stderr cc-result)))))]
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
    ;; Best-effort; a quota-exhausted final LLM call shouldn't tip a verified
    ;; run into :error. The user can re-run later if they want the meta-md.
    (try
      (generate-project-claude-md! run work-dir)
      (catch Exception e
        (log/warn "Skipped project CLAUDE.md generation"
                  {:message (ex-message e)
                   :cause   (:cause (ex-data e))}))))
  (store/persist-run! run)
  (emit! run {:type :run-complete :reason reason :iteration iteration}))

(def ^:private polish-claude-md
  (str "> **Polish pass — comments only.**\n"
       "> Verification has just passed for this project. This iteration is "
       "ONLY for back-filling brief WHY comments on non-obvious code in the "
       "files that already exist. You must NOT change behaviour, NOT add new "
       "files, NOT remove or rename anything.\n"
       ">\n"
       "> Rules:\n"
       "> - Add a comment ONLY where a reader would otherwise be confused: "
       "workarounds, business rules, subtle invariants, surprising behaviour, "
       "API quirks, performance hacks. Be selective — most code does not "
       "need a comment.\n"
       "> - One short line per comment. No multi-paragraph blocks. No "
       "decorative banners.\n"
       "> - Skip self-explanatory code. Skip tests. Skip obvious helpers.\n"
       "> - Do NOT add docstrings on every function — only the few with a "
       "non-obvious contract that the function name does not already convey.\n"
       "> - If a file already has adequate comments, leave it alone.\n"
       "> - In your chat output: no preamble, no trailing summary. Edit the "
       "files and stop.\n"))

(defn run-polish-pass!
  "Best-effort comment back-fill after verification. Runs one CC pass with a
   purpose-built CLAUDE.md, commits the result. Quota exhaustion or any
   subprocess error is logged and skipped — the verified run still finalises.
   Public so the standalone --polish-only CLI command can reuse it."
  [run work-dir iteration]
  (try
    (let [config        (:config run)
          timeout-ms    (:timeout-ms config 300000)
          exec-cfg      (or (:executor-model-config config)
                            (:model-config config))
          primary-model (:model-id exec-cfg)]
      (cc/write-claude-md! work-dir polish-claude-md)
      (emit! run {:type :polish-started :iteration iteration})
      (let [result (run-with-fallback!
                     run
                     {:work-dir     work-dir
                      :prompt       "Add WHY comments per CLAUDE.md. Be selective; do not change behaviour."
                      :timeout-ms   timeout-ms
                      :model        primary-model
                      :model-config exec-cfg
                      :on-output    (fn [line]
                                      (swap! (:live-output run) str line "\n")
                                      (emit! run {:type      :output-line
                                                  :iteration iteration
                                                  :line      line}))}
                     config)]
        (cond
          (rate-limited? result)
          (do (log/warn "Polish pass quota-exhausted — skipping")
              (emit! run {:type      :polish-skipped
                          :reason    :exhausted
                          :iteration iteration}))

          (pos? (:exit result 0))
          (do (log/warn "Polish pass exited non-zero — skipping commit"
                        {:exit (:exit result)})
              (emit! run {:type      :polish-skipped
                          :reason    :non-zero-exit
                          :exit      (:exit result)
                          :iteration iteration}))

          :else
          (do (git-sh work-dir "add" "-A")
              (git-sh work-dir "commit" "-q" "--allow-empty"
                      "-m" (str "morpheus:polish iter-" iteration))
              (emit! run {:type :polish-complete :iteration iteration})))))
    (catch Exception e
      (log/warn "Polish pass failed — finalising verified run anyway"
                {:message (ex-message e)})
      (emit! run {:type      :polish-skipped
                  :reason    :error
                  :message   (ex-message e)
                  :iteration iteration}))))

(defn- finish-verified!
  "Shared exit-path for verified runs: commit the phase, optionally run the
   polish pass, then finalise. The three places that transition to a
   verified-done state share this so the polish-pass toggle stays consistent."
  [run run-config work-dir iteration commit-phase!]
  (commit-phase!)
  (when (:polish-pass? run-config)
    (run-polish-pass! run work-dir iteration))
  (finish-run! run run-config work-dir :verified iteration))

(defn- init-run! [run run-config snapshot]
  (let [run-id    (:run-id run)
        resuming? (boolean snapshot)
        work-dir  (if resuming?
                    (do (log/info "Resuming from snapshot"
                                  {:work-dir       (:work-dir snapshot)
                                   :from-iteration (inc (count (:iterations snapshot)))})
                        (:work-dir snapshot))
                    (let [wd (or (when-let [s (stable-work-dir run-config)]
                                   (.mkdirs (io/file s))
                                   s)
                                 (cc/make-work-dir! run-id "wiggum"))]
                      ;; only seed from project-dir if the work-dir is empty —
                      ;; a populated stable work-dir means a previous run was
                      ;; aborted and the user wants to keep going from where it left
                      (when (and (:project-dir run-config)
                                 (work-dir-empty? wd))
                        (let [pd (:project-dir run-config)]
                          (when (.exists (io/file pd))
                            (log/info "Seeding work-dir from project-dir" {:src pd :dst wd})
                            (shell/sh "sh" "-c" (str "cp -r " pd "/. " wd "/") :dir wd))))
                      (when-not (work-dir-empty? wd)
                        (log/info "Reusing populated work-dir" {:dir wd}))
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
                                  :objective (:objective run-config)})]
    ;; Both bootstrap and resume route through build-next-packet in execute!:
    ;; resume regenerates the next packet from snapshot evidence (avoiding the
    ;; old wasted-iteration where the snapshotted packet was re-executed),
    ;; bootstrap calls it with empty evidence and a synthesized seed packet.
    ;; The supervisor LLM call is wrapped in retry-on-exhaustion in execute!.
    {:work-dir      work-dir
     :initial-state initial-state
     :start-iter    (if resuming? (inc (count (:iterations snapshot))) 1)
     :resuming?     resuming?
     :prior-packet  (if resuming?
                      (:control-packet snapshot)
                      (supervisor/bootstrap run-config))}))

(defn- iteration-context [run run-config ev iteration]
  (let [verified?        (verification-passed? (:verification ev))
        exhausted?       (boolean (:exhausted? ev))
        checkpoint-every (:checkpoint-every run-config)
        milestone-hit?   (and checkpoint-every
                              (zero? (mod iteration checkpoint-every)))
        phase-ended?     (boolean (:phase-ended? ev))
        review           (:review ev)
        review-threshold (or (:review-threshold run-config) :high)
        review-pause?    (and phase-ended?
                              (judge/requires-pause? review review-threshold))]
    {:verified?      verified?
     :exhausted?     exhausted?
     :quota-message  (:quota-message ev)
     :phase-ended?   phase-ended?
     :milestone?     milestone-hit?
     :review         review
     :review-pause?  review-pause?
     :pause?         (or exhausted?
                         (:step-once? @(:control run))
                         milestone-hit?
                         review-pause?)}))

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

    ;; Same as default :step but strips the judge's review from the latest
    ;; iteration's evidence — the supervisor won't see the judge complaints
    ;; when planning the next packet. For when the judge is stuck or wrong.
    :ignore
    (let [feedback (when (seq (:feedback action)) (:feedback action))]
      (swap! (:iterations run)
             (fn [iters]
               (if (seq iters)
                 (update iters (dec (count iters)) dissoc :review)
                 iters)))
      (if (and (:verified? ctx) (not feedback))
        (do (finish-verified! run run-config work-dir iteration commit-phase!)
            [:done])
        (do (commit-phase!)
            (when feedback
              (swap! (:max-iters run) max (inc iteration)))
            [:recur (inc iteration)
                    (next-packet-after-step run run-config control-packet
                                            initial-state action)])))

    (let [feedback (when (seq (:feedback action)) (:feedback action))]
      (cond
        ;; Token / quota exhaustion — retry the same packet on resume. No
        ;; point asking the supervisor to plan from an error message; bump
        ;; the iteration cap so the failed attempt doesn't consume a slot.
        (:exhausted? ctx)
        (do (swap! (:max-iters run) max (inc iteration))
            [:recur (inc iteration) control-packet])

        ;; If the human supplied feedback, they expect another iteration to
        ;; act on it — do not auto-terminate on verified, and bump the
        ;; iteration cap if the next iteration would exceed it.
        (and (:verified? ctx) (not feedback))
        (do (finish-verified! run run-config work-dir iteration commit-phase!)
            [:done])

        :else
        (do (commit-phase!)
            (when feedback
              (swap! (:max-iters run) max (inc iteration)))
            [:recur (inc iteration)
                    (next-packet-after-step run run-config control-packet
                                            initial-state action)])))))

(defn emit-pause!
  "Marks the run paused and announces it. The actual `<! resume-ch` happens
   inline in execute! — `<!` only works lexically inside (go ...). Public
   so retry-on-exhaustion can expand to call it from caller namespaces."
  [run iteration ctx]
  (set-state! run :paused)
  (emit! run (merge {:type :run-paused :iteration iteration}
                    (select-keys ctx [:verified? :milestone? :phase-ended?
                                      :review :review-pause?
                                      :exhausted? :quota-message]))))

(defmacro retry-on-exhaustion
  "Wraps an LLM-throwing expression so a quota-exhausted call pauses the run
   instead of crashing it. Must be invoked inside the (go ...) block in
   execute! — the expansion contains `<!` and `recur` that only work in
   that lexical context.

   On :cause :exhausted: emits :run-paused with :exhausted? true and the
   quota message, awaits resume on (:resume-ch run), and retries body. On
   user :abort during the pause: throws ex-info {:cause :user-abort} which
   the top-level catch in execute! recognises as a clean exit (no :run-error)."
  [run iteration & body]
  `(loop []
     (let [r# (try
                {:ok (do ~@body)}
                (catch clojure.lang.ExceptionInfo e#
                  (let [d# (ex-data e#)]
                    (if (= :exhausted (:cause d#))
                      {:exhausted true
                       :message   (or (:message d#) (.getMessage e#))}
                      (throw e#)))))]
       (if (:exhausted r#)
         (do (emit-pause! ~run ~iteration
                          {:exhausted? true :quota-message (:message r#)})
             (let [a# (<! (:resume-ch ~run))]
               (set-state! ~run :running)
               (if (= :abort (:action a#))
                 (do (set-state! ~run :aborted)
                     (emit! ~run {:type :run-aborted})
                     (throw (ex-info "user aborted run during exhaustion pause"
                                     {:cause :user-abort})))
                 (recur))))
         (:ok r#)))))

(defn execute!
  "Starts the Wiggum loop in a go-block. Returns the run map immediately.
   Sends events to (:event-ch run). Pauses after each iteration in step-once
   mode. Auto-resumes from project-dir/morpheus-run-snapshot.edn when the
   work-dir from that snapshot still exists."
  [run-id run-config]
  (let [run-config (render-run-config run-config)
        run        (create-run run-id run-config)
        snapshot   (load-snapshot run-config)]
    (go
      (set-state! run :running)
      (try
        (let [{:keys [work-dir initial-state start-iter resuming? prior-packet]}
              (init-run! run run-config snapshot)
              start-packet (retry-on-exhaustion run start-iter
                             (build-next-packet
                               run-config
                               prior-packet
                               (if resuming?
                                 (recent-evidence (:iterations run) 3)
                                 [])
                               nil
                               initial-state))]
          (loop [iteration      start-iter
                 control-packet start-packet]
            (reset! (:control-packet run) control-packet)
            (cond
              (> iteration @(:max-iters run))
              (do (set-state! run :done)
                  (emit! run {:type :run-complete :reason :max-iterations
                              :iterations (dec iteration)}))

              (= :aborted @(:state run))
              (emit! run {:type :run-aborted :run-id run-id})

              :else
              (let [ev (retry-on-exhaustion run iteration
                         (run-iteration! run work-dir iteration control-packet))
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
                          outcome (retry-on-exhaustion run iteration
                                    (apply-resume-action action run run-config work-dir
                                                         iteration control-packet
                                                         initial-state ctx commit-phase!))]
                      (case (first outcome)
                        :recur (recur (nth outcome 1) (nth outcome 2))
                        :done  nil)))

                  (:verified? ctx)
                  (finish-verified! run run-config work-dir iteration commit-phase!)

                  :else
                  (do (commit-phase!)
                      (recur (inc iteration)
                             (retry-on-exhaustion run iteration
                               (build-next-packet run-config control-packet
                                                  (recent-evidence (:iterations run) 3)
                                                  (consume-steer! run)
                                                  initial-state)))))))))

        (catch Exception e
          (let [d (when (instance? clojure.lang.ExceptionInfo e) (ex-data e))]
            (if (= :user-abort (:cause d))
              ;; Clean abort during an exhaustion pause — state is already
              ;; :aborted and :run-aborted has been emitted by the macro.
              (log/info "Wiggum run aborted during exhaustion pause"
                        {:run-id run-id})
              (let [;; A snapshot exists when at least one iteration completed.
                    ;; Resume from there is safe because the engine now always
                    ;; regenerates the next packet from snapshot evidence.
                    iters     (count @(:iterations run))
                    snap?     (pos? iters)
                    proj-dir  (:project-dir run-config)]
                (log/error e "Wiggum run crashed"
                           {:run-id run-id :message (.getMessage e)})
                (set-state! run :error)
                (emit! run (cond-> {:type        :run-error
                                    :message     (.getMessage e)
                                    :iteration   iters
                                    :continuable? snap?}
                             (and snap? proj-dir)
                             (assoc :project-dir proj-dir)))))))))
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
