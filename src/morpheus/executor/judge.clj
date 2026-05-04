(ns morpheus.executor.judge
  "LLM-as-judge for Wiggum iterations. Inspects the git diff against the
   previously-accepted state and scores it against the control packet's
   expected-files, constraints, and anti-goals.

   Returns:
     {:score          0..10
      :recommendation :continue | :restore | :needs-review
      :summary        one-sentence quality call
      :violations     [{:file :type :severity :reason} ...]}

   `requires-pause?` decides whether to halt for human input even in auto mode."
  (:require
   [clojure.string          :as str]
   [taoensso.timbre         :as log]
   [morpheus.executor.llm   :as llm]))

(def ^:private max-diff-chars 20000)

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "\n\n…[truncated " (- (count s) n) " chars]")
    (or s "")))

(defn- review-prompt
  [{:keys [objective expected-files constraints anti-goals
           files-written files-edited files-deleted diff success-check]}]
  (->> [(str "You are a strict code reviewer watching an autonomous coding agent. "
             "Score this iteration's output and flag any violations of the scope "
             "or constraints the agent was told to respect.")

        (str "## Overall objective\n" objective)

        (when success-check
          (str "## Overall success check (do not hold the agent to this for one iteration)\n`"
               success-check "`"))

        (str "## What THIS iteration was supposed to produce\n"
             (if (seq expected-files)
               (str/join "\n" (map #(str "- " %) expected-files))
               "(no expected-files declared — judge leniently)"))

        (when (seq constraints)
          (str "## Constraints the agent was told to obey\n"
               (str/join "\n" (map #(str "- " %) constraints))))

        (when (seq anti-goals)
          (str "## Anti-goals\n"
               (str/join "\n" (map #(str "- " %) anti-goals))))

        (str "## Files the agent created this iteration (" (count files-written) ")\n"
             (if (seq files-written)
               (str/join "\n" (map #(str "  + " %) files-written))
               "(none)"))

        (str "## Files the agent edited this iteration (" (count files-edited) ")\n"
             (if (seq files-edited)
               (str/join "\n" (map #(str "  ~ " %) files-edited))
               "(none)"))

        (when (seq files-deleted)
          (str "## Files the agent deleted this iteration (" (count files-deleted) ")\n"
               (str/join "\n" (map #(str "  - " %) files-deleted))))

        (str "## Git diff of this iteration (against previously-accepted state)\n"
             "```diff\n" (truncate diff max-diff-chars) "\n```")

        (str/join "\n"
          ["## Scoring rubric"
           "- score: integer 0..10 (10 = perfect, 0 = total disregard of scope/constraints)"
           "- violations: concrete things the agent did wrong. Each violation has:"
           "    file     — relative path (or \"\" if cross-cutting)"
           "    type     — one of: overwrite-unexpected, wrote-outside-scope,"
           "               hallucinated-api, ignored-constraint, created-slop,"
           "               wrong-path, deleted-valid-content, other"
           "    severity — low | medium | high"
           "    reason   — one short sentence"
           "- recommendation:"
           "    \"continue\"     — output is acceptable; move on"
           "    \"restore\"      — revert this iteration; the damage outweighs the value"
           "    \"needs-review\" — borderline, a human should decide"
           "- summary: one sentence on the iteration's quality"

           ""
           "Be strict about edits to files NOT listed in expected-files — that is"
           "the single most common failure mode and almost always a violation."])

        (str/join "\n"
          ["## Output — JSON only, no markdown fences, no prose"
           "{"
           "  \"score\": 7,"
           "  \"violations\": ["
           "    {\"file\": \"src/foo.clj\", \"type\": \"overwrite-unexpected\","
           "     \"severity\": \"high\", \"reason\": \"edited file not in expected-files\"}"
           "  ],"
           "  \"recommendation\": \"restore\","
           "  \"summary\": \"Edited two pre-existing files that were out of scope.\""
           "}"])]
       (remove nil?)
       (str/join "\n\n")))

(defn- as-keyword [v]
  (when v
    (-> v name str/lower-case (str/replace #"_" "-") keyword)))

(defn- normalise-review [raw]
  (let [rec (as-keyword (:recommendation raw))]
    {:score          (some-> (:score raw) int)
     :recommendation (if (#{:continue :restore :needs-review} rec) rec :continue)
     :summary        (:summary raw)
     :violations     (mapv (fn [v]
                             {:file     (or (:file v) "")
                              :type     (or (as-keyword (:type v)) :other)
                              :severity (or (#{:low :medium :high}
                                              (as-keyword (:severity v)))
                                            :low)
                              :reason   (or (:reason v) "")})
                           (or (:violations raw) []))}))

(defn review!
  "Runs the judge against one iteration. Returns a canonical review map or nil
   on error so the run keeps going when the judge misbehaves."
  [model-config ctx]
  (try
    (log/info "Judge reviewing iteration"
              {:expected (count (:expected-files ctx))
               :written  (count (:files-written ctx))
               :edited   (count (:files-edited ctx))
               :diff-chars (count (or (:diff ctx) ""))})
    (-> (llm/complete-json model-config (review-prompt ctx))
        normalise-review)
    (catch Exception e
      (log/warn "Judge failed — continuing without review" {:message (ex-message e)})
      nil)))

(defn- severity-rank [s]
  (case s :high 3 :medium 2 :low 1 0))

(defn requires-pause?
  "Truthy when the review warrants halting for human review. Pauses on
   :restore recommendations or any violation at/above `threshold` severity."
  ([review] (requires-pause? review :high))
  ([review threshold]
   (when review
     (or (= :restore (:recommendation review))
         (let [t (severity-rank threshold)]
           (boolean (some #(>= (severity-rank (:severity %)) t)
                          (:violations review))))))))
