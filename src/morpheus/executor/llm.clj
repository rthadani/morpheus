(ns morpheus.executor.llm
  "LLM calls — dispatches on :agent in model-config.
     :claude (default) — shells out to the claude CLI; :provider inside
                         routes to :ollama, :kimi, :minimax, or Anthropic.
     :pi               — shells out to the pi CLI; :provider inside
                         routes to whatever pi supports (google, openai, …)."
  (:require
   [clojure.data.json           :as json]
   [clojure.java.shell          :as shell]
   [clojure.string              :as str]
   [taoensso.timbre             :as log]
   [morpheus.executor.agent     :as agent]
   [morpheus.executor.pi-agent  :as pi]))

(def default-model "claude-haiku-4-5-20251001")

(def rate-limit-signals
  "Substrings appearing in claude CLI stdout/stderr when API quota or rate
   limits block the call. Wiggum reads this set too — single source of truth."
  #{"rate_limit_error" "overloaded_error" "429" "too many requests"
    "rate limit" "tokens will renew" "usage limit" "will reset at"
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
        "Claude reported a rate limit / quota error.")))

(defn- throw-cli-error!
  "Surfaces a typed :cause :exhausted exception when the CLI's output looks
   like a rate-limit / quota error, so callers can pause-and-retry instead
   of crashing. Falls back to the legacy generic error otherwise."
  [tag {:keys [exit out err]}]
  (let [combined (str out "\n" err)]
    (if (rate-limited? combined)
      (throw (ex-info (str tag " (rate limited)")
                      {:cause   :exhausted
                       :message (exhaustion-message combined)
                       :exit    exit
                       :stderr  err}))
      (throw (ex-info tag {:exit exit :stderr err})))))

(defn- prompt-with-system
  "Prepends the system prompt as a delimited block when present."
  [system prompt]
  (if (seq system)
    (str system "\n\n---\n\n" prompt)
    prompt))

(defn- anthropic-env
  "Env overrides that point the claude CLI at an Anthropic-compatible base-url
   authenticated by api-key, pinning every model slot to model-id."
  [base-url api-key model-id]
  (-> (into {} (System/getenv))
      (assoc "ANTHROPIC_BASE_URL"             base-url
             "ANTHROPIC_AUTH_TOKEN"           api-key
             "ANTHROPIC_API_KEY"              ""
             "ANTHROPIC_MODEL"                model-id
             "ANTHROPIC_DEFAULT_OPUS_MODEL"   model-id
             "ANTHROPIC_DEFAULT_SONNET_MODEL" model-id
             "ANTHROPIC_DEFAULT_HAIKU_MODEL"  model-id
             "CLAUDE_CODE_SUBAGENT_MODEL"     model-id
             "ENABLE_TOOL_SEARCH"             "false")))

(defn- complete-ollama [{:keys [model-id system]} prompt]
  (when (str/blank? model-id)
    (throw (ex-info "model-id required for :ollama provider" {:provider :ollama})))
  (let [args   ["ollama" "launch" "claude" "--model" model-id "--yes"
                "--" "--print" "--dangerously-skip-permissions"]
        result (apply shell/sh (concat args [:in (prompt-with-system system prompt)]))]
    (when (pos? (:exit result))
      (throw-cli-error! "ollama launch claude error" result))
    (str/trim (:out result))))

(defn- complete-anthropic-compatible
  "Routes the claude CLI through a third-party Anthropic-compatible backend
   (:kimi, :minimax). api-key comes from env-var; defaults supply the model and
   base-url when model-config omits them."
  [provider env-var default-model-id default-base-url
   {:keys [model-id base-url system]} prompt]
  (let [api-key (System/getenv env-var)]
    (when (str/blank? api-key)
      (throw (ex-info (str env-var " env var not set") {:provider provider})))
    (let [model-id (or model-id default-model-id)
          env      (anthropic-env (or base-url default-base-url) api-key model-id)
          args     (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
                     model-id (concat ["--model" model-id]))
          result   (apply shell/sh (concat args [:in (prompt-with-system system prompt) :env env]))]
      (when (pos? (:exit result))
        (throw-cli-error! (str "claude CLI error (" (name provider) ")") result))
      (str/trim (:out result)))))

(defn- complete-kimi [model-config prompt]
  (complete-anthropic-compatible
    :kimi "MOONSHOT_API_KEY" "kimi-k2.5" "https://api.moonshot.ai/anthropic"
    model-config prompt))

(defn- complete-minimax [model-config prompt]
  (complete-anthropic-compatible
    :minimax "MINIMAX_API_KEY" "MiniMax-M2.7" "https://api.minimax.chat/anthropic"
    model-config prompt))

(defn- complete-anthropic
  "Default :claude-agent provider — calls the claude CLI targeting
   api.anthropic.com directly (no env overrides)."
  [{:keys [model-id system] :or {model-id default-model}} prompt]
  (let [args   (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
                 model-id (concat ["--model" model-id]))
        result (apply shell/sh (concat args [:in (prompt-with-system system prompt)]))]
    (when (pos? (:exit result))
      (throw-cli-error! "claude CLI error" result))
    (str/trim (:out result))))

(defmulti complete
  "Dispatches on :agent — :claude (default) or :pi.
   The :claude agent shells out to the claude CLI and routes to its own
   providers (:ollama, :kimi, :minimax, or default Anthropic).
   The :pi agent shells out to the pi CLI and routes to whatever provider
   pi is configured for (:google, :openai, :anthropic, …)."
  (fn [model-config _prompt]
    (or (:agent model-config) :claude)))

(defmethod complete :claude
  [{:keys [provider model-id] :as model-config} prompt]
  (log/debug "LLM call" {:agent :claude :provider (or provider :claude) :model model-id :prompt-chars (count prompt)})
  (case provider
    :ollama  (complete-ollama   model-config prompt)
    :kimi    (complete-kimi     model-config prompt)
    :minimax (complete-minimax  model-config prompt)
    (complete-anthropic model-config prompt)))

(defmethod complete :pi
  [{:keys [model-id] :as model-config} prompt]
  (log/debug "LLM call" {:agent :pi :model model-id :prompt-chars (count prompt)})
  (pi/complete model-config prompt))

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
    (log/debug "complete-json raw response" {:chars (count raw) :first-50 (subs raw 0 (min 50 (count raw)))})
    (try
      (json/read-str json-str :key-fn keyword)
      (catch Exception e
        (log/error "JSON parse failed" {:raw raw})
        (throw (ex-info (str "LLM returned non-JSON: " (subs raw 0 (min 200 (count raw))))
                        {:raw raw :cause e}))))))
