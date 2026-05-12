(ns morpheus.executor.llm
  "LLM calls — dispatches on :provider in model-config. All providers shell out
   to the claude CLI; non-Anthropic providers just override env vars to point
   at an Anthropic-compatible endpoint.
     :claude  (default) — api.anthropic.com
     :ollama            — `ollama launch claude` (auto-pulls model)
     :kimi              — api.moonshot.ai/anthropic (reads MOONSHOT_API_KEY)"
  (:require
   [clojure.data.json   :as json]
   [clojure.java.shell  :as shell]
   [clojure.string      :as str]
   [taoensso.timbre     :as log]))

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

(defn- complete-ollama [{:keys [model-id system]} prompt]
  (when (str/blank? model-id)
    (throw (ex-info "model-id required for :ollama provider" {:provider :ollama})))
  (let [full-prompt (if (seq system)
                      (str system "\n\n---\n\n" prompt)
                      prompt)
        args        ["ollama" "launch" "claude" "--model" model-id "--yes"
                     "--" "--print" "--dangerously-skip-permissions"]
        result      (apply shell/sh (concat args [:in full-prompt]))]
    (when (pos? (:exit result))
      (throw-cli-error! "ollama launch claude error" result))
    (str/trim (:out result))))

(defn- complete-kimi
  [{:keys [model-id base-url system]
    :or   {model-id "kimi-k2.5"
           base-url "https://api.moonshot.ai/anthropic"}}
   prompt]
  (let [api-key (System/getenv "MOONSHOT_API_KEY")]
    (when (str/blank? api-key)
      (throw (ex-info "MOONSHOT_API_KEY env var not set" {:provider :kimi}))))
  (let [full-prompt (if (seq system)
                      (str system "\n\n---\n\n" prompt)
                      prompt)
        env         (-> (into {} (System/getenv))
                        (assoc "ANTHROPIC_BASE_URL"             base-url
                               "ANTHROPIC_AUTH_TOKEN"           (System/getenv "MOONSHOT_API_KEY")
                               "ANTHROPIC_API_KEY"              ""
                               "ANTHROPIC_MODEL"                model-id
                               "ANTHROPIC_DEFAULT_OPUS_MODEL"   model-id
                               "ANTHROPIC_DEFAULT_SONNET_MODEL" model-id
                               "ANTHROPIC_DEFAULT_HAIKU_MODEL"  model-id
                               "CLAUDE_CODE_SUBAGENT_MODEL"     model-id
                               "ENABLE_TOOL_SEARCH"             "false"))
        args        (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
                      model-id (concat ["--model" model-id]))
        result      (apply shell/sh (concat args [:in full-prompt :env env]))]
    (when (pos? (:exit result))
      (throw-cli-error! "claude CLI error (kimi)" result))
    (str/trim (:out result))))

(defn- complete-claude
  [{:keys [model-id system]
    :or   {model-id default-model}}
   prompt]
  (let [full-prompt (if (seq system)
                      (str system "\n\n---\n\n" prompt)
                      prompt)
        args        (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
                      model-id (concat ["--model" model-id]))
        result      (apply shell/sh (concat args [:in full-prompt]))]
    (when (pos? (:exit result))
      (throw-cli-error! "claude CLI error" result))
    (str/trim (:out result))))

(defn complete
  "Calls claude --print, optionally routed through a non-Anthropic backend.
   Dispatches on :provider — :ollama, :kimi, or :claude (default)."
  [{:keys [provider model-id] :as model-config} prompt]
  (log/debug "LLM call" {:provider (or provider :claude) :model model-id :prompt-chars (count prompt)})
  (case provider
    :ollama (complete-ollama model-config prompt)
    :kimi   (complete-kimi   model-config prompt)
    (complete-claude model-config prompt)))

(defn- extract-json-object
  "Pulls the outermost { ... } from text — robust to prose or fences around it."
  [text]
  (let [start (.indexOf text "{")
        end   (.lastIndexOf text "}")]
    (if (and (>= start 0) (> end start))
      (subs text start (inc end))
      text)))

(defn complete-json
  "Like complete but extracts a JSON object from the response and parses it."
  [model-config prompt]
  (let [json-prompt (str prompt "\n\nRespond with valid JSON only. No preamble, no markdown fences.")
        raw         (complete model-config json-prompt)
        json-str    (-> raw
                        (str/replace #"(?s)```[a-z]*\n?" "")
                        (str/replace #"```" "")
                        str/trim
                        extract-json-object)]
    (log/debug "complete-json raw response" {:chars (count raw) :first-50 (subs raw 0 (min 50 (count raw)))})
    (try
      (json/read-str json-str :key-fn keyword)
      (catch Exception e
        (log/error "JSON parse failed" {:raw raw})
        (throw (ex-info (str "Supervisor returned non-JSON: " (subs raw 0 (min 200 (count raw))))
                        {:raw raw :cause e}))))))
