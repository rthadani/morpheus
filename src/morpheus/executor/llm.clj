(ns morpheus.executor.llm
  "LLM calls — dispatches on :provider in model-config.
   :provider :claude  (default) — calls claude --print CLI
   :provider :ollama             — calls Ollama HTTP API directly"
  (:require
   [clojure.data.json   :as json]
   [clojure.java.shell  :as shell]
   [clojure.string      :as str]
   [org.httpkit.client  :as http]
   [taoensso.timbre     :as log]))

(def default-model "claude-haiku-4-5-20251001")

(defn- complete-ollama
  [{:keys [model-id base-url system temperature max-tokens]
    :or   {base-url    "http://localhost:11434"
           temperature 0.2
           max-tokens  4096}}
   prompt]
  (let [full-prompt (if (seq system)
                      (str system "\n\n---\n\n" prompt)
                      prompt)
        {:keys [status body error]}
        @(http/post (str base-url "/api/generate")
                    {:headers {"Content-Type" "application/json"}
                     :body    (json/write-str {:model   model-id
                                               :prompt  full-prompt
                                               :stream  false
                                               :options {:temperature temperature
                                                         :num_predict max-tokens}})
                     :timeout 300000})]
    (when error
      (throw (ex-info "Ollama connection error" {:cause error})))
    (when (>= status 400)
      (throw (ex-info "Ollama error" {:status status :body body})))
    (-> body (json/read-str :key-fn keyword) :response str/trim)))

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
      (throw (ex-info "claude CLI error" {:exit (:exit result) :stderr (:err result)})))
    (str/trim (:out result))))

(defn complete
  "Calls the appropriate LLM backend based on :provider in model-config.
   :provider :ollama  — Ollama HTTP API (default base-url: http://localhost:11434)
   :provider :claude  — claude --print CLI (default, used when :provider is absent)"
  [{:keys [provider model-id] :as model-config} prompt]
  (log/debug "LLM call" {:provider (or provider :claude) :model model-id :prompt-chars (count prompt)})
  (if (= provider :ollama)
    (complete-ollama model-config prompt)
    (complete-claude model-config prompt)))

(defn- extract-json-object
  "Extracts the first complete JSON object from text by finding the
   outermost { ... } pair. Handles LLM responses that wrap JSON in prose
   or markdown fences."
  [text]
  (let [start (.indexOf text "{")
        end   (.lastIndexOf text "}")]
    (if (and (>= start 0) (> end start))
      (subs text start (inc end))
      text)))

(defn complete-json
  "Like complete but extracts a JSON object from the response.
   Returns parsed Clojure map. Robust to markdown fences and surrounding prose."
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
