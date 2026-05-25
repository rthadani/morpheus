(ns morpheus.executor.claude-code-agent
  "Runs Claude Code as a subprocess for task and planning nodes. CC handles
   file I/O, bash, and codebase context — the orchestrator just hands it a
   working directory and a CLAUDE.md."
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.java.shell  :as shell]
   [clojure.java.io     :as io]
   [clojure.string      :as str]
   [clojure.data.json   :as json]
   [taoensso.timbre     :as log]
   [morpheus.executor.agent :as agent]))

(defn render-claude-md
  "Builds the CLAUDE.md for a node. :claude-md may be a string (with {{slot}}
   interpolation), a context keyword, or nil (a minimal default is generated)."
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
                            "The task described above is complete.")))
        rendered (reduce-kv
                   (fn [s k v]
                     (str/replace s
                                  (re-pattern (str "\\{\\{" (name k) "\\}\\}"))
                                  (str (or v ""))))
                   template
                   inputs)]
    (if-let [steer (get context ::steer)]
      (str rendered "\n\n## Human guidance\n\n" steer "\n")
      rendered)))

(defn- parse-stream-line
  "Parses one stream-json line. Returns {:activity :result :cost-usd} —
   :result and :cost-usd only present on the final result line."
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

(defn claude-available? []
  (zero? (:exit (shell/sh "which" "claude"))))

(defn- build-cmd+env
  "Returns {:cmd [...] :env-overrides {...}} for the given provider. Mirrors
   morpheus.executor.llm dispatch so the executor can target non-Anthropic
   Anthropic-compatible backends."
  [{:keys [provider base-url] :or {provider :claude}} model auto? prompt]
  (let [claude-args (cond-> ["--print" "--verbose"
                             "--output-format" "stream-json"]
                      model (concat ["--model" model])
                      auto? (concat ["--dangerously-skip-permissions"])
                      true  (concat [prompt])
                      true  vec)]
    (case provider
      :ollama
      (do
        (when (str/blank? model)
          (throw (ex-info ":model required for :ollama provider" {:provider :ollama})))
        {:cmd (vec (concat ["ollama" "launch" "claude" "--model" model "--yes" "--"]
                           claude-args))
         :env-overrides {}})

      :kimi
      (let [api-key (System/getenv "MOONSHOT_API_KEY")]
        (when (str/blank? api-key)
          (throw (ex-info "MOONSHOT_API_KEY env var not set" {:provider :kimi})))
        {:cmd (vec (cons "claude" claude-args))
         :env-overrides {"ANTHROPIC_BASE_URL"             (or base-url "https://api.moonshot.ai/anthropic")
                         "ANTHROPIC_AUTH_TOKEN"           api-key
                         "ANTHROPIC_API_KEY"              ""
                         "ANTHROPIC_MODEL"                (or model "")
                         "ANTHROPIC_DEFAULT_OPUS_MODEL"   (or model "")
                         "ANTHROPIC_DEFAULT_SONNET_MODEL" (or model "")
                         "ANTHROPIC_DEFAULT_HAIKU_MODEL"  (or model "")
                         "CLAUDE_CODE_SUBAGENT_MODEL"     (or model "")
                         "ENABLE_TOOL_SEARCH"             "false"}})

      {:cmd (vec (cons "claude" claude-args))
       :env-overrides {}})))

(defn run!
  "Runs Claude Code non-interactively in work-dir, streaming stdout via
   :on-output. :model-config dispatches the backend (:claude / :kimi / :ollama).
   Returns a map with :stdout :stderr :exit :files-written :before-snapshot
   :after-snapshot :duration-ms :model :provider :work-dir."
  [{:keys [work-dir prompt timeout-ms project-dir model model-config auto? on-output]
    :or   {timeout-ms 300000 auto? true}}]
  (log/info "Claude Code run starting" {:work-dir work-dir
                                        :provider (or (:provider model-config) :claude)
                                        :model    model
                                        :prompt-chars (count prompt)})
  (let [before-snapshot (agent/snapshot-files work-dir)
        started-at      (System/currentTimeMillis)
        provider        (or (:provider model-config) :claude)
        {:keys [cmd env-overrides]} (build-cmd+env model-config model auto? prompt)
        result-text     (atom nil)
        cost-usd        (atom nil)
        on-line         (fn [line]
                          (let [parsed (parse-stream-line line)]
                            (when (and on-output (:activity parsed))
                              (on-output (:activity parsed)))
                            (when (:result parsed)
                              (reset! result-text (:result parsed)))
                            (when (:cost-usd parsed)
                              (reset! cost-usd (:cost-usd parsed)))))
        sub             (agent/run-subprocess!
                          {:work-dir      work-dir
                           :project-dir   project-dir
                           :timeout-ms    timeout-ms
                           :cmd           cmd
                           :env-overrides env-overrides
                           :on-line       on-line})
        after-snapshot  (agent/snapshot-files work-dir)]
    (log/info "Claude Code run complete"
              {:exit (:exit sub) :out-chars (count (:stdout-buf sub))
               :duration-ms (:duration-ms sub) :cost-usd @cost-usd})
    (when (not (str/blank? (:stderr-buf sub)))
      (log/warn "Claude Code stderr" (:stderr-buf sub)))
    (agent/build-result
      {:work-dir        work-dir
       :stdout          (:stdout-buf sub)
       :stderr          (:stderr-buf sub)
       :exit            (:exit sub)
       :duration-ms     (:duration-ms sub)
       :prompt-chars    (count prompt)
       :result-text     @result-text
       :cost-usd        @cost-usd
       :model           model
       :provider        (name provider)
       :before-snapshot before-snapshot
       :after-snapshot  after-snapshot})))

(defn run-plan!
  "Plan-mode: analyses the codebase and returns a structured plan without
   writing any files. Used by :planning nodes."
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
