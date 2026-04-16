(ns morpheus.executor.claude-code
  "Runs Claude Code as a subprocess for task and planning nodes.
   Claude Code handles file I/O, bash, and codebase context natively —
   the orchestrator just hands it a working directory and a CLAUDE.md."
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.java.shell  :as shell]
   [clojure.java.io     :as io]
   [clojure.string      :as str]
   [clojure.data.json   :as json]
   [taoensso.timbre     :as log])
  (:import
   [java.nio.file Files Path]
   [java.nio.file.attribute FileAttribute]
   [java.io BufferedReader InputStreamReader]))

;; ──────────────────────────────────────────
;; Working directory management
;; ──────────────────────────────────────────

(defn make-work-dir!
  "Creates a temp directory for a node's Claude Code session.
   Returns the absolute path string."
  [run-id node-id]
  (let [prefix (str "morpheus-" (str run-id) "-" (name node-id) "-")
        path   (Files/createTempDirectory prefix (make-array FileAttribute 0))]
    (str path)))

(defn list-written-files
  "Returns seq of relative paths for all files under dir,
   excluding CLAUDE.md and hidden files."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (map #(.getPath %))
       (remove #(str/includes? % "CLAUDE.md"))
       (remove #(str/includes? % "/."))
       (map #(str/replace % (str dir "/") ""))))

(defn snapshot-files
  "Returns {relative-path -> last-modified-ms} for all non-hidden, non-CLAUDE.md
   files under dir. Used by evidence/classify-changes to distinguish new vs edited files."
  [dir]
  (let [base (io/file dir)]
    (->> (file-seq base)
         (filter #(.isFile %))
         (remove #(str/includes? (.getPath %) "/."))
         (remove #(str/includes? (.getName %) "CLAUDE.md"))
         (into {} (map (fn [f]
                         [(str/replace (.getPath f) (str dir "/") "")
                          (.lastModified f)]))))))

;; ──────────────────────────────────────────
;; CLAUDE.md generation
;; ──────────────────────────────────────────

(defn write-claude-md!
  "Writes a CLAUDE.md file into the working directory."
  [work-dir content]
  (spit (str work-dir "/CLAUDE.md") content))

(defn render-claude-md
  "Builds the CLAUDE.md string for a node from its config and resolved inputs.

   :claude-md in the node can be:
     - a plain string (used as-is, with {{slot}} interpolation)
     - a keyword pointing to a context key that holds the md string
     - nil (a minimal default is generated)"
  [node inputs context]
  (let [template (cond
                   (string? (:claude-md node))
                   (:claude-md node)

                   (keyword? (:claude-md node))
                   (get context (:claude-md node))

                   :else
                   (str "# Task: " (name (:id node)) "\n\n"
                        "## Goal\n"
                        (or (:prompt node) "Complete the task described below.")
                        "\n\n"
                        "## Inputs\n"
                        (pr-str inputs)
                        "\n\n"
                        "## Done when\n"
                        (or (:done-check node)
                            "The task described above is complete.")))]
    ;; interpolate {{slot}} placeholders from inputs
    (let [rendered (reduce-kv
                     (fn [s k v]
                       (str/replace s
                                    (re-pattern (str "\\{\\{" (name k) "\\}\\}"))
                                    (str (or v ""))))
                     template
                     inputs)]
      (if-let [steer (get context ::steer)]
        (str rendered "\n\n## Human guidance\n\n" steer "\n")
        rendered))))

;; ──────────────────────────────────────────
;; Stream-JSON parsing
;; ──────────────────────────────────────────

(defn parse-stream-line
  "Parses one stream-json line from claude --output-format stream-json.
   Returns a map with:
     :activity  — human-readable string of what CC just did (nil if nothing notable)
     :result    — final text output (only present on the result line)
     :cost-usd  — cost in USD (only present on the result line)"
  [line]
  (try
    (let [ev (json/read-str line :key-fn keyword)]
      (case (:type ev)
        "assistant"
        (let [blocks (get-in ev [:message :content] [])
              parts  (keep (fn [b]
                             (case (:type b)
                               "text"     (let [t (str/trim (:text b ""))]
                                            (when (seq t) (str "💭 " t)))
                               "tool_use" (let [n   (:name b)
                                                inp (some-> b :input
                                                            (dissoc :description)
                                                            pr-str)]
                                            (str "🔧 " n (when inp (str " " inp))))
                               nil))
                           blocks)]
          {:activity (when (seq parts) (str/join " · " parts))})

        "result"
        {:result   (or (:result ev) "")
         :cost-usd (:cost_usd ev)}

        {}))
    (catch Exception _ {})))

;; ──────────────────────────────────────────
;; Claude Code invocation
;; ──────────────────────────────────────────

(defn claude-available? []
  (zero? (:exit (shell/sh "which" "claude"))))

(defn run!
  "Runs Claude Code non-interactively in work-dir.
   Streams stdout line-by-line via on-output callback as the process runs.

   Options:
     :work-dir    required — directory CC runs in
     :prompt      required — task instruction
     :timeout-ms  optional — default 300000
     :project-dir optional — path copied into work-dir before run
     :model       optional — model id string passed via --model flag
     :auto?       optional — skip all permission prompts (default true)
     :on-output   optional — (fn [line]) called for each stdout line in real-time

   Returns:
   {:stdout          <full output string>
    :stderr          <stderr string>
    :exit            <exit code>
    :files-written   <seq of relative file paths — kept for DAG engine compat>
    :before-snapshot <{path -> mtime} before CC ran>
    :after-snapshot  <{path -> mtime} after CC finished>
    :started-at      <epoch ms>
    :duration-ms     <elapsed ms>
    :model           <model id or nil>
    :provider        \"anthropic\"
    :work-dir        <working directory path>}"
  [{:keys [work-dir prompt timeout-ms project-dir model auto? on-output]
    :or   {timeout-ms 300000 auto? true}}]
  (log/info "Claude Code run starting" {:work-dir work-dir
                                         :prompt-chars (count prompt)})
  (let [_ (when project-dir
            (shell/sh "sh" "-c"
                      (str "cp -r " project-dir "/* " work-dir "/")
                      :dir work-dir))
        before-snapshot (snapshot-files work-dir)
        started-at      (System/currentTimeMillis)
        claude-cmd      (vec (concat ["claude" "--print" "--verbose"
                                               "--output-format" "stream-json"]
                                     (when model ["--model" model])
                                     (when auto? ["--dangerously-skip-permissions"])
                                     [prompt]))
        ;; stdbuf forces line-buffered stdout so we get real-time output via pipe
        stdbuf?         (zero? (:exit (shell/sh "which" "stdbuf")))
        cmd             (if stdbuf?
                          (vec (concat ["stdbuf" "-oL" "-eL"] claude-cmd))
                          claude-cmd)
        pb              (doto (ProcessBuilder. cmd)
                          (.directory (io/file work-dir))
                          (.redirectErrorStream false))
        _               (.putAll (.environment pb) (System/getenv))
        process         (.start pb)
        stdout-buf      (StringBuilder.)
        stderr-buf      (StringBuilder.)
        ;; drain stderr on a separate thread to avoid blocking
        stderr-thread   (doto (Thread.
                                (fn []
                                  (with-open [rdr (BufferedReader.
                                                    (InputStreamReader.
                                                      (.getErrorStream process)))]
                                    (loop [line (.readLine rdr)]
                                      (when line
                                        (.append stderr-buf line)
                                        (.append stderr-buf "\n")
                                        (recur (.readLine rdr)))))))
                          (.setDaemon true)
                          .start)]
    ;; read stdout line-by-line; parse stream-json for activity + final result
    (let [result-text (atom nil)
          cost-usd    (atom nil)]
      (with-open [rdr (BufferedReader. (InputStreamReader. (.getInputStream process)))]
        (loop [line (.readLine rdr)]
          (when line
            (.append stdout-buf line)
            (.append stdout-buf "\n")
            (let [parsed (parse-stream-line line)]
              (when-let [r (:result parsed)]   (reset! result-text r))
              (when-let [c (:cost-usd parsed)] (reset! cost-usd c))
              (when (and on-output (:activity parsed))
                (on-output (:activity parsed))))
            (recur (.readLine rdr)))))
      (.join stderr-thread)
      (let [exited?   (.waitFor process (quot timeout-ms 1000) java.util.concurrent.TimeUnit/SECONDS)
            _         (when-not exited? (.destroyForcibly process))
            exit-code (if exited? (.exitValue process) 1)
            stdout    (or @result-text (str stdout-buf))
            stderr    (str stderr-buf)
            duration-ms (- (System/currentTimeMillis) started-at)
            after-snapshot (snapshot-files work-dir)]
        (log/info "Claude Code run complete"
                  {:exit exit-code :out-chars (count stdout) :duration-ms duration-ms
                   :cost-usd @cost-usd})
        (when (not (str/blank? stderr))
          (log/warn "Claude Code stderr" stderr))
        {:stdout          stdout
         :stderr          stderr
         :exit            exit-code
         :files-written   (list-written-files work-dir)
         :before-snapshot before-snapshot
         :after-snapshot  after-snapshot
         :started-at      started-at
         :duration-ms     duration-ms
         :prompt-chars    (count prompt)
         :cost-usd        @cost-usd
         :model           model
         :provider        "anthropic"
         :work-dir        work-dir}))))

;; ──────────────────────────────────────────
;; Plan mode — uses Claude Code's analysis without writing files
;; ──────────────────────────────────────────

(defn run-plan!
  "Runs Claude Code in plan mode: analyses the codebase and returns
   a structured plan without executing any writes.
   Useful for :planning nodes that need real codebase context."
  [{:keys [work-dir prompt project-dir timeout-ms]
    :or   {timeout-ms 120000}}]
  (log/info "Claude Code plan mode" {:work-dir work-dir})
  (let [_ (when project-dir
            (shell/sh "sh" "-c"
                      (str "cp -r " project-dir "/* " work-dir "/")
                      :dir work-dir))
        result (shell/sh
                 "claude"
                 "--print"
                 "--dangerously-skip-permissions"
                 (str prompt
                      "\n\nIMPORTANT: This is a planning pass. "
                      "Analyse the codebase and produce a detailed plan. "
                      "Do NOT write or modify any files.")
                 :dir work-dir)]
    {:stdout        (:out result)
     :exit          (:exit result)
     :files-written []
     :work-dir      work-dir}))
