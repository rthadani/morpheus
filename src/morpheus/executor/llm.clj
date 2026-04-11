(ns morpheus.executor.llm
  "LLM calls via the Claude Code CLI subprocess.
   Uses whatever auth Claude Code is configured with (OAuth or API key) —
   no separate ANTHROPIC_API_KEY required."
  (:require
   [clojure.data.json  :as json]
   [clojure.java.shell :as shell]
   [clojure.string     :as str]
   [taoensso.timbre    :as log]))

(def default-model "claude-sonnet-4-6")

(defn complete
  "Runs claude --print with the given prompt. Returns the text response.
   model-config keys: :model-id :system (temperature/max-tokens ignored — CLI handles defaults)"
  [{:keys [model-id system]
    :or   {model-id default-model}}
   prompt]
  (log/debug "LLM call via claude CLI" {:model model-id :prompt-chars (count prompt)})
  (let [full-prompt (if (seq system)
                      (str system "\n\n---\n\n" prompt)
                      prompt)
        args        (cond-> ["claude" "--print" "--dangerously-skip-permissions"]
                      model-id (concat ["--model" model-id]))
        result      (apply shell/sh (concat args [full-prompt]))]
    (when (pos? (:exit result))
      (throw (ex-info "claude CLI error" {:exit (:exit result) :stderr (:err result)})))
    (str/trim (:out result))))

(defn complete-json
  "Like complete but extracts a JSON object from the response.
   Returns parsed Clojure map."
  [model-config prompt]
  (let [json-prompt (str prompt "\n\nRespond with valid JSON only. No preamble, no markdown fences.")
        raw         (complete model-config json-prompt)
        ;; strip any accidental markdown fences
        cleaned     (-> raw
                        (str/replace #"(?s)^```[a-z]*\n?" "")
                        (str/replace #"```$" "")
                        str/trim)]
    (json/read-str cleaned :key-fn keyword)))
