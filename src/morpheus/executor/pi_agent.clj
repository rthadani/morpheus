(ns morpheus.executor.pi-agent
  "Pi agent executor — mirrors morpheus.executor.claude-code-agent.
   Shells out to the pi CLI (`pi -p`) for both work mode and plan mode.
   Dispatches on :provider internally so each backend can carry its own
   env-var or flag logic, just like the :claude agent in llm.clj.

   Pi does not have a built-in plan mode (see pi README:
   'skips features like sub agents and plan mode').  We simulate it by
   appending a planning instruction to the prompt and declaring that no
   files were written."
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.data.json   :as json]
   [clojure.java.shell  :as shell]
   [clojure.string      :as str]
   [taoensso.timbre     :as log]
   [morpheus.executor.agent :as agent])
  (:import
   [java.io BufferedReader InputStreamReader]))

;; ---------------------------------------------------------------------------
;; CLI availability

(defn pi-available?
  "Returns true when the `pi` CLI is on PATH."
  []
  (zero? (:exit (shell/sh "which" "pi"))))

;; ---------------------------------------------------------------------------
;; Provider-specific completions (for llm/complete dispatch)

(defn- run-pi!
  "Low-level pi invocation for the lightweight llm/complete path.
   Does NOT stream; returns trimmed stdout via shell/sh."
  [{:keys [provider model-id system api-key]} prompt]
  (let [args (cond-> ["pi" "-p"]
               provider (concat ["--provider" (name provider)])
               model-id (concat ["--model" model-id])
               system   (concat ["--system-prompt" system])
               api-key  (concat ["--api-key" api-key])
               true     (concat ["--no-context-files"]))
        result (apply shell/sh (concat args [:in prompt]))]
    (when (pos? (:exit result))
      (agent/throw-cli-error!
        (str "pi CLI error" (when provider (str " (" (name provider) ")")))
        result))
    (str/trim (:out result))))

(defn- complete-default [cfg prompt] (run-pi! cfg prompt))
(defn- complete-google   [cfg prompt] (run-pi! (assoc cfg :provider :google)    prompt))
(defn- complete-openai   [cfg prompt] (run-pi! (assoc cfg :provider :openai)    prompt))
(defn- complete-anthropic [cfg prompt] (run-pi! (assoc cfg :provider :anthropic) prompt))
(defn- complete-kimi     [cfg prompt] (run-pi! (assoc cfg :provider :kimi)      prompt))
(defn- complete-minimax  [cfg prompt] (run-pi! (assoc cfg :provider :minimax)   prompt))
(defn- complete-ollama   [cfg prompt] (run-pi! (assoc cfg :provider :ollama)    prompt))

(defn complete
  "Calls `pi -p`, routing to a provider-specific implementation.
   model-config keys:
     :provider  – pi provider name (:google, :openai, :anthropic,
                   :kimi, :minimax, :ollama, or nil for default)
     :model-id  – model pattern or ID
     :system    – system prompt string
     :api-key   – API key string (optional; env vars preferred)
   Returns the trimmed stdout string."
  [{:keys [provider model-id] :as model-config} prompt]
  (when-not (pi-available?)
    (throw (ex-info "pi CLI not found on PATH"
                    {:hint "npm install -g @earendil-works/pi-coding-agent"})))
  (log/debug "Pi agent call" {:provider (or provider :default)
                              :model    model-id
                              :prompt-chars (count prompt)})
  (case provider
    :google     (complete-google    model-config prompt)
    :openai     (complete-openai    model-config prompt)
    :anthropic  (complete-anthropic model-config prompt)
    :kimi       (complete-kimi      model-config prompt)
    :minimax    (complete-minimax   model-config prompt)
    :ollama     (complete-ollama    model-config prompt)
    (complete-default model-config prompt)))

;; ---------------------------------------------------------------------------
;; JSON helper (for llm/complete-json dispatch)

(defn complete-json
  "Like complete but extracts a JSON object from the response and parses it."
  [model-config prompt]
  (let [json-prompt (str prompt "\n\nRespond with valid JSON only. No preamble, no markdown fences.")
        raw         (complete model-config json-prompt)
        json-str    (-> raw
                        (str/replace #"(?s)```[a-z]*\n?" "")
                        (str/replace #"```" "")
                        str/trim
                        agent/extract-json-object)]
    (log/debug "complete-json raw response"
               {:chars (count raw)
                :first-50 (subs raw 0 (min 50 (count raw)))})
    (try
      (json/read-str json-str :key-fn keyword)
      (catch Exception e
        (log/error "JSON parse failed" {:raw raw})
        (throw (ex-info (str "Pi agent returned non-JSON: "
                             (subs raw 0 (min 200 (count raw))))
                        {:raw raw :cause e}))))))

;; ---------------------------------------------------------------------------
;; Streaming run! (mirrors claude-code-agent/run!)

(defn- parse-pi-json-line
  "Parses one pi --mode json line. Returns a map with keys:
     :text   — content text delta (for streaming display)
     :tool   — tool-use description (for activity display)
     :usage  — final usage/cost map from message_end / turn_end
     :done?  — true when the turn/agent is finished"
  [line]
  (try
    (let [ev (json/read-str line :key-fn keyword)]
      (case (:type ev)
        "message_update"
        (let [ame (:assistantMessageEvent ev)]
          (case (:type ame)
            "text_delta"   {:text (:delta ame)}
            "text_start"   {:text ""}
            "thinking_delta" {:text nil}
            "tool_use"
            (let [n   (:name ame)
                  inp (some-> ame :input pr-str)]
              {:tool (str "🔧 " n (when inp (str " " inp)))})
            {}))

        "message_end"
        (let [u (get-in ev [:message :usage])]
          {:usage (when u
                    {:input-tokens  (:input u)
                     :output-tokens (:output u)
                     :cost-usd      (get-in u [:cost :total])})
           :done? true})

        "turn_end"
        (let [u (get-in ev [:message :usage])]
          {:usage (when u
                    {:input-tokens  (:input u)
                     :output-tokens (:output u)
                     :cost-usd      (get-in u [:cost :total])})
           :done? true})

        "agent_end"
        {:done? true}

        {}))
    (catch Exception _ {})))

(defn- build-pi-cmd
  "Builds the pi command vector from model-config."
  [{:keys [provider model-id]}]
  (cond-> ["pi" "-p" "--mode" "json" "--no-context-files"]
    provider (conj "--provider" (name provider))
    model-id (conj "--model" model-id)))

(defn run!
  "Runs pi non-interactively in work-dir, streaming stdout via :on-output.
   Returns a map with :stdout :stderr :exit :files-written :before-snapshot
   :after-snapshot :duration-ms :model :provider :work-dir.

   Options:
     :work-dir     — temp dir to run in
     :prompt       — string sent to pi via stdin
     :project-dir  — copied into work-dir before running
     :timeout-ms   — per-run timeout (default 300000)
     :model-config — {:provider :model-id} for pi backend routing
     :on-output    — callback fn receiving each visible line/activity"
  [{:keys [work-dir prompt timeout-ms project-dir model-config on-output]
    :or   {timeout-ms 300000}}]
  (log/info "Pi run starting" {:work-dir work-dir
                               :provider (or (:provider model-config) :default)
                               :model    (:model-id model-config)
                               :prompt-chars (count prompt)})
  (let [before-snapshot (agent/snapshot-files work-dir)
        result-text     (atom "")
        cost-usd        (atom nil)
        on-line         (fn [line]
                          (let [parsed (parse-pi-json-line line)]
                            (when (and on-output (:text parsed))
                              (on-output (:text parsed)))
                            (when (and on-output (:tool parsed))
                              (on-output (:tool parsed)))
                            (when (:usage parsed)
                              (reset! cost-usd (get-in parsed [:usage :cost-usd])))
                            (when (:text parsed)
                              (swap! result-text str (:text parsed)))))
        sub             (agent/run-subprocess!
                          {:work-dir    work-dir
                           :prompt      prompt
                           :project-dir project-dir
                           :timeout-ms  timeout-ms
                           :cmd         (build-pi-cmd model-config)
                           :on-line     on-line})
        after-snapshot  (agent/snapshot-files work-dir)]
    (log/info "Pi run complete"
              {:exit (:exit sub)
               :out-chars (count (:stdout-buf sub))
               :duration-ms (:duration-ms sub)
               :cost-usd @cost-usd})
    (when (not (str/blank? (:stderr-buf sub)))
      (log/warn "Pi stderr" (:stderr-buf sub)))
    (agent/build-result
      {:work-dir        work-dir
       :stdout          (:stdout-buf sub)
       :stderr          (:stderr-buf sub)
       :exit            (:exit sub)
       :duration-ms     (:duration-ms sub)
       :prompt-chars    (count prompt)
       :result-text     @result-text
       :cost-usd        @cost-usd
       :model           (:model-id model-config)
       :provider        (name (or (:provider model-config) :default))
       :before-snapshot before-snapshot
       :after-snapshot  after-snapshot})))

(defn run-plan!
  "Plan-mode: analyses the codebase and returns a structured plan without
   writing any files. Used by :planning nodes."
  [{:keys [work-dir prompt project-dir timeout-ms]
    :or   {timeout-ms 120000}}]
  (log/info "Pi plan mode" {:work-dir work-dir})
  (agent/seed-project! project-dir work-dir)
  (let [result (shell/sh
                 "pi" "-p" "--no-context-files"
                 :in (str prompt agent/planning-suffix)
                 :dir work-dir)]
    {:stdout        (:out result)
     :exit          (:exit result)
     :files-written []
     :work-dir      work-dir}))
