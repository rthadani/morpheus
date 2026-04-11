(ns morpheus.executor.claude-code
  "Runs Claude Code as a subprocess for task and planning nodes.
   Claude Code handles file I/O, bash, and codebase context natively —
   the orchestrator just hands it a working directory and a CLAUDE.md."
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.java.shell  :as shell]
   [clojure.java.io     :as io]
   [clojure.string      :as str]
   [taoensso.timbre     :as log])
  (:import
   [java.nio.file Files Path]
   [java.nio.file.attribute FileAttribute]))

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
    (reduce-kv
      (fn [s k v]
        (str/replace s
                     (re-pattern (str "\\{\\{" (name k) "\\}\\}"))
                     (str (or v ""))))
      template
      inputs)))

;; ──────────────────────────────────────────
;; Claude Code invocation
;; ──────────────────────────────────────────

(defn claude-available? []
  (zero? (:exit (shell/sh "which" "claude"))))

(defn run!
  "Runs Claude Code non-interactively in work-dir.
   prompt is the task instruction passed via --print.

   Options:
     :work-dir    required — directory CC runs in
     :prompt      required — task instruction
     :timeout-ms  optional — default 300000
     :project-dir optional — path copied into work-dir before run
     :model       optional — model id string passed via --model flag
     :auto?       optional — skip all permission prompts (default true)

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
  [{:keys [work-dir prompt timeout-ms project-dir model auto?]
    :or   {timeout-ms 300000 auto? true}}]
  (log/info "Claude Code run starting" {:work-dir work-dir
                                         :prompt-chars (count prompt)})
  (let [;; if a project-dir is set, copy it into work-dir before snapshotting
        _ (when project-dir
            (shell/sh "sh" "-c"
                      (str "cp -r " project-dir "/* " work-dir "/")
                      :dir work-dir))
        before-snapshot (snapshot-files work-dir)
        started-at      (System/currentTimeMillis)
        model-args      (when model ["--model" model])
        auto-args       (when auto? ["--dangerously-skip-permissions"])
        result          (apply shell/sh
                          (concat ["claude"
                                   "--print"]         ; non-interactive, print output and exit
                                  model-args
                                  auto-args
                                  [prompt
                                   :dir work-dir
                                   :env (into {} (System/getenv))]))
        duration-ms     (- (System/currentTimeMillis) started-at)
        after-snapshot  (snapshot-files work-dir)]
    (log/info "Claude Code run complete"
              {:exit (:exit result) :out-chars (count (:out result)) :duration-ms duration-ms})
    (when (not (str/blank? (:err result)))
      (log/warn "Claude Code stderr" (:err result)))
    {:stdout          (:out result)
     :stderr          (:err result)
     :exit            (:exit result)
     :files-written   (list-written-files work-dir)   ; kept for DAG engine compat
     :before-snapshot before-snapshot
     :after-snapshot  after-snapshot
     :started-at      started-at
     :duration-ms     duration-ms
     :model           model
     :provider        "anthropic"
     :work-dir        work-dir}))

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
