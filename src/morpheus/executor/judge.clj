(ns morpheus.executor.judge
  "LLM-as-judge reviewer for Wiggum iterations.

   After the executor Claude Code subprocess finishes an iteration, the judge
   inspects the git diff against the previously-accepted state and scores the
   work against the control packet's expected-files, constraints, and anti-goals.

   Returns a canonical review map:

     {:score          0..10
      :recommendation :continue | :restore | :needs-review
      :summary        one-sentence quality call
      :violations     [{:file :type :severity :reason} ...]}

   The main loop uses `requires-pause?` to decide whether to halt for human
   input even when step-mode is off."
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

(defn- normalise-review
  "Coerces raw JSON into a canonical review map with keyword severity /
   recommendation. Guards against missing fields so a sloppy judge response
   doesn't crash the loop."
  [raw]
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
   on error (so the run keeps going even when the judge misbehaves).

   ctx keys:
     :objective      — overall goal string
     :expected-files — vec of paths this iteration was supposed to produce
     :constraints    — vec of constraint strings
     :anti-goals     — vec of anti-goal strings
     :files-written  — vec (from evidence)
     :files-edited   — vec (from evidence)
     :files-deleted  — vec (from evidence)
     :success-check  — optional shell string for context
     :diff           — raw git diff output for this iteration"
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
  "Returns truthy when the review warrants halting for human review.
   Pauses only on blocker-class signals — soft :needs-review calls do not pause.
   Triggers:
     - recommendation is :restore (judge says: roll this back)
     - any violation at or above `threshold` severity (default :high)"
  ([review] (requires-pause? review :high))
  ([review threshold]
   (when review
     (or (= :restore (:recommendation review))
         (let [t (severity-rank threshold)]
           (boolean (some #(>= (severity-rank (:severity %)) t)
                          (:violations review))))))))
