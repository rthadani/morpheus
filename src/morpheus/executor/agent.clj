(ns morpheus.executor.agent
  "Shared infrastructure for agent subprocess runners (Claude Code, pi, etc.).
   File helpers, rate-limit handling, JSON extraction, and the core
   subprocess orchestration (ProcessBuilder, stdbuf, stderr thread,
   stdout streaming, timeout)."
  (:require
   [clojure.java.io     :as io]
   [clojure.java.shell  :as shell]
   [clojure.string      :as str]
   [taoensso.timbre     :as log])
  (:import
   [java.io BufferedReader InputStreamReader OutputStreamWriter]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; File helpers

(defn make-work-dir!
  "Creates a temp directory for a node's session. Returns absolute path.
   Prefix should be a short slug like 'morpheus-' or 'morpheus-pi-'."
  [prefix run-id node-id]
  (let [full-prefix (str prefix (str run-id) "-" (name node-id) "-")
        path (Files/createTempDirectory full-prefix (make-array FileAttribute 0))]
    (str path)))

(defn list-written-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (map #(.getPath %))
       (remove #(str/includes? % "CLAUDE.md"))
       (remove #(str/includes? % "/."))
       (map #(str/replace % (str dir "/") ""))))

(defn snapshot-files
  "Returns {relative-path -> last-modified-ms} for non-hidden, non-CLAUDE.md
   files. Used to distinguish new vs edited files after a run."
  [dir]
  (let [base (io/file dir)]
    (->> (file-seq base)
         (filter #(.isFile %))
         (remove #(str/includes? (.getPath %) "/."))
         (remove #(str/includes? (.getName %) "CLAUDE.md"))
         (into {} (map (fn [f]
                         [(str/replace (.getPath f) (str dir "/") "")
                          (.lastModified f)]))))))

(defn write-claude-md! [work-dir content]
  (spit (str work-dir "/CLAUDE.md") content))

;; ---------------------------------------------------------------------------
;; Rate-limit / error handling

(def rate-limit-signals
  "Substrings that indicate a rate or quota limit from any provider."
  #{"rate_limit_error" "overloaded_error" "429" "too many requests"
    "rate limit" "tokens will renew" "usage limit" "will reset at"
    "quota exceeded" "insufficient quota" "billing" "credit"
    "resource exhausted" "try again later"
    "claude usage limit reached" "you have reached your"})

(defn rate-limited?
  "True when the given text contains any rate-limit signal."
  [text]
  (let [out (str/lower-case (str text))]
    (boolean (some #(str/includes? out %) rate-limit-signals))))

(defn exhaustion-message
  "First line in the text that mentions a rate-limit signal, trimmed.
   Falls back to a generic message when nothing matches."
  [text]
  (let [lines (str/split-lines (str text))
        match (->> lines
                   (filter #(let [l (str/lower-case %)]
                              (some (fn [s] (str/includes? l s)) rate-limit-signals)))
                   first)]
    (or (some-> match str/trim not-empty)
        "Provider reported a rate limit / quota error.")))

(defn throw-cli-error!
  "Surfaces a typed :cause :exhausted exception when the CLI's output looks
   like a rate-limit / quota error, so callers can pause-and-retry instead
   of crashing. Falls back to a generic error otherwise."
  [tag {:keys [exit out err]}]
  (let [combined (str out "\n" err)]
    (if (rate-limited? combined)
      (throw (ex-info (str tag " (rate limited)")
                      {:cause   :exhausted
                       :message (exhaustion-message combined)
                       :exit    exit
                       :stderr  err}))
      (throw (ex-info tag {:exit exit :stderr err :out out})))))

;; ---------------------------------------------------------------------------
;; JSON extraction

(defn extract-json-object
  "Pulls the outermost { ... } from text — robust to prose or fences around it."
  [text]
  (let [start (.indexOf text "{")
        end   (.lastIndexOf text "}")]
    (if (and (>= start 0) (> end start))
      (subs text start (inc end))
      text)))

;; ---------------------------------------------------------------------------
;; Subprocess plumbing

(defn stdbuf-cmd
  "Optionally prefixes cmd with `stdbuf -oL -eL` if stdbuf is on PATH."
  [cmd]
  (let [stdbuf? (zero? (:exit (shell/sh "which" "stdbuf")))]
    (if stdbuf?
      (vec (concat ["stdbuf" "-oL" "-eL"] cmd))
      cmd)))

(defn start-process!
  "Creates and starts a ProcessBuilder in work-dir with optional env overrides."
  [work-dir cmd env-overrides]
  (let [pb (doto (ProcessBuilder. cmd)
             (.directory (io/file work-dir))
             (.redirectErrorStream false))]
    (.putAll (.environment pb) (System/getenv))
    (when (seq env-overrides)
      (.putAll (.environment pb) env-overrides))
    (.start pb)))

(defn start-stderr-thread!
  "Starts a daemon thread that drains the process's stderr into a
   StringBuilder. Returns the thread."
  [process ^StringBuilder stderr-buf]
  (doto (Thread.
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
    .start))

(defn run-subprocess!
  "Shared subprocess runner for agent CLIs.
   Handles project-dir seeding, ProcessBuilder, stdbuf, stderr thread,
   stdout line-by-line reading with a per-line callback, timeout, and
   optional stdin prompt writing.

   Returns {:stdout-buf :stderr-buf :exit :duration-ms}.
   The caller is responsible for parsing stdout and extracting result/cost.

   Options:
     :work-dir      — temp dir to run in
     :prompt        — string written to process stdin (optional)
     :project-dir   — copied into work-dir before running (optional)
     :timeout-ms    — per-run timeout (default 300000)
     :cmd           — command vector (already built by caller)
     :env-overrides — map of env var overrides (optional)
     :on-line       — callback fn (fn [line]) for each stdout line"
  [{:keys [work-dir prompt timeout-ms project-dir
           cmd env-overrides on-line]
    :or   {timeout-ms 300000}}]
  (when project-dir
    (shell/sh "sh" "-c"
              (str "cp -r " project-dir "/* " work-dir "/")
              :dir work-dir))
  (let [started-at    (System/currentTimeMillis)
        full-cmd      (stdbuf-cmd cmd)
        process       (start-process! work-dir full-cmd env-overrides)
        stdout-buf    (StringBuilder.)
        stderr-buf    (StringBuilder.)
        stderr-thread (start-stderr-thread! process stderr-buf)]
    ;; Write prompt to stdin if provided, then close it so the process
    ;; knows input is finished.
    (when (seq prompt)
      (with-open [w (OutputStreamWriter. (.getOutputStream process))]
        (.write w ^String prompt)
        (.flush w)))
    (with-open [rdr (BufferedReader. (InputStreamReader. (.getInputStream process)))]
      (loop [line (.readLine rdr)]
        (when line
          (.append stdout-buf line)
          (.append stdout-buf "\n")
          (when on-line
            (on-line line))
          (recur (.readLine rdr)))))
    (.join stderr-thread)
    (let [exited?   (.waitFor process (quot timeout-ms 1000)
                              java.util.concurrent.TimeUnit/SECONDS)
          _         (when-not exited? (.destroyForcibly process))
          exit-code (if exited? (.exitValue process) 1)
          duration  (- (System/currentTimeMillis) started-at)]
      {:stdout-buf  (str stdout-buf)
       :stderr-buf  (str stderr-buf)
       :exit        exit-code
       :duration-ms duration})))

(defn build-result
  "Assembles the standard agent-run result map from subprocess output and
   file snapshots.  Callers supply the stdout string and optionally a
   result-text override (e.g. extracted from a JSON stream)."
  [{:keys [work-dir stdout stderr exit duration-ms prompt-chars
           result-text cost-usd model provider
           before-snapshot after-snapshot]}]
  (let [stdout-str (or result-text stdout)]
    {:stdout          stdout-str
     :stderr          stderr
     :exit            exit
     :files-written   (list-written-files work-dir)
     :before-snapshot before-snapshot
     :after-snapshot  after-snapshot
     :started-at      (- (System/currentTimeMillis) duration-ms)
     :duration-ms     duration-ms
     :prompt-chars    prompt-chars
     :cost-usd        cost-usd
     :model           model
     :provider        provider
     :work-dir        work-dir}))
