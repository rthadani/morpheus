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

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "\n\n…[truncated " (- (count s) n) " chars]")
    (or s "")))

(def ^:private code-rubric
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
     "the single most common failure mode and almost always a violation."]))

(def ^:private spec-rubric
  (str/join "\n"
    ["## You are reviewing a Wiggum implementation spec, not application code."
     "The agent produces ONE EDN file per run that another Wiggum loop will read"
     "and execute. Defects in this spec propagate into the downstream build, so"
     "be strict — finding gaps now is much cheaper than the agent grinding"
     "against a broken spec for 30 iterations."
     ""
     "## Checklist (walk it explicitly; flag every miss)"
     ""
     "1. Parse safety"
     "   * File parses via clojure.edn/read-string (strict, NOT clojure's reader)."
     "   * No unescaped `\"` inside long string values — embedded code/JSON must"
     "     use single-quoted 'literals' or backtick `code` style. Unescaped `\"`"
     "     terminates the outer string and produces unparseable EDN."
     "   * No invalid escapes (`\\$`, `\\|`, `\\'`)."
     ""
     "2. Schema completeness"
     "   * Required keys present: :objective :success-check :constraints"
     "     :anti-goals :max-iterations :step-once? :generate-claude-md?"
     "     :judge-mode :review-threshold"
     "     :executor-model-config :supervisor-model-config."
     "   * :fallback-model and :fallback-delay-ms required for claude-routed"
     "     runs; OMITTED for :pi runs (no automatic fallback wired for pi)."
     "   * :step-once? must be a literal boolean (true or false), never a"
     "     string. The wiggum executor reads it with (boolean ...) and a"
     "     string 'false' is truthy — a stringly-typed value silently flips"
     "     the run into step-once mode."
     "   * :executor-model-config and :supervisor-model-config MUST be maps"
     "     (e.g. {:model-id \"claude-sonnet-4-6\"}), NEVER bare model-id"
     "     strings. A string value crashes wiggum at (:model-id model-config)."
     "   * :judge-mode must be :code or :research (never :spec — that mode"
     "     is for reviewing specs themselves, not downstream runs)."
     "   * :review-threshold must be one of :none :low :medium :high."
     "   * MEDIUM/COMPLEX: :acceptance-criteria required."
     "   * COMPLEX: :checkpoint-every required."
     "   * Dead keys absent: :review? (replaced by :review-threshold :none),"
     "     :temperature, :max-tokens, :auth-token-env (morpheus does not read"
     "     any of these — env-var names are hardcoded per provider in"
     "     build-cmd+env)."
     ""
     "3. Provider/model correctness"
     "   * Model IDs match the current dispatch table; flag stale ones"
     "     (claude-opus-4-5 is wrong; current Opus is claude-opus-4-7)."
     "   * Non-default claude-routed configs include :provider — only"
     "     :ollama, :kimi, and :minimax are wired in claude_code_agent.clj's"
     "     build-cmd+env. :glm, :openrouter, and :custom are NOT supported"
     "     and must be flagged as provider-misconfig."
     "   * Pi-routed configs include :agent :pi (and optionally :provider for"
     "     a specific pi backend: :google :openai :anthropic :kimi :minimax"
     "     :ollama)."
     "   * Provider/agent keyword (:ollama :kimi :minimax :anthropic :pi)"
     "     stripped from the slug and the objective title."
     ""
     "4. Phase consistency (the most common defect class — be exhaustive)"
     "   * Every column / table / function / namespace mentioned in one phase has"
     "     a populator in some phase. For each <noun> introduced, walk the"
     "     remaining phases and confirm something produces it."
     "   * Every \"see Phase N\" or \"Phase N's deliverable\" reference points at"
     "     a phase that actually exists in this spec."
     "   * The success-check's commands reference deliverables that some phase in"
     "     the objective actually produces."
     "   * Constraints don't contradict anti-goals (or vice versa)."
     "   * :acceptance-criteria gates use real shell-runnable commands (test"
     "     scripts, file existence, curl checks) — not just a generic"
     "     `clojure -M:test` for every phase."
     ""
     "5. Visual artifact (UI specs only)"
     "   * If the description implies a UI, exactly ONE visual artifact is"
     "     declared (output/mockup.html for web, mockup.txt for TUI,"
     "     expected-session.txt for CLI)."
     "   * Exactly ONE Visual reference phase, placed before any phase that"
     "     implements the UI."
     "   * No CSS values or layout specifics restated in subsequent phases —"
     "     they belong in the artifact, not in the prose."
     ""
     "6. Depth match"
     "   * SIMPLE description  → spec ~50-150 lines."
     "   * MEDIUM             → ~150-500 lines."
     "   * COMPLEX            → ~1000+ lines, every namespace/column/test"
     "     fixture enumerated. The reference for COMPLEX is"
     "     graphs/options-trader/options-trader.edn — a thin sketch against a"
     "     COMPLEX description is the most common failure mode and an automatic"
     "     high-severity violation."
     ""
     "## Scoring"
     "- score: 0..10 (10 = ready to run; ≤4 = the spec is broken)."
     "- violations: each has file (path of the spec or \"\" cross-cutting),"
     "  type, severity (low | medium | high), and a one-sentence reason."
     "  Allowed types:"
     "    missing-key, dead-key, stale-model-id, parse-unsafe,"
     "    phase-disconnect, undefined-reference, visual-artifact-wrong,"
     "    under-detailed, provider-misconfig, contradicts-constraint, other."
     "- recommendation: continue | restore | needs-review."
     "- summary: one sentence on whether the spec is ready to ship."
     ""
     "Treat phase-disconnect and under-detailed as HIGH severity — those"
     "silently produce a worse downstream build."]))

(def ^:private research-rubric
  (str/join "\n"
    ["## You are reviewing research output, not application code."
     "The agent has fetched web material, read documents, and produced written"
     "content. Be strict about citation discipline and faithfulness to source —"
     "those are the failure modes that turn research into fiction."
     ""
     "## Checklist (walk it explicitly; flag every miss)"
     ""
     "1. Citation discipline"
     "   * Every factual claim ties to a specific source: a URL, a filing"
     "     accession, a paper DOI, a document path the agent actually read."
     "     'According to a recent report...' with no citation is a violation."
     "   * Cited URLs/sources actually appear in the agent's evidence (links"
     "     it fetched, files it read). Sources that don't show up in the diff"
     "     or the agent's tool history are likely fabricated."
     "   * Direct quotations are verbatim from the cited source. Paraphrased"
     "     content presented inside quotation marks is fabrication, not paraphrase."
     ""
     "2. Source diversity"
     "   * For any non-trivial claim — one that could plausibly be wrong — at"
     "     least two INDEPENDENT sources support it. A single press release"
     "     sourced from a single page is not a triangulated finding."
     "   * Independent ≠ different URLs. Two pages both citing the same"
     "     original report count as one source."
     "   * Primary sources (filings, transcripts, official press releases,"
     "     published data) preferred over commentary or aggregator pages"
     "     where both are available."
     ""
     "3. Faithfulness to source"
     "   * Claims do not extrapolate beyond what the cited material actually"
     "     states. 'Revenue grew 12%' citing a source that only says 'revenue"
     "     grew' is a violation — either find the number or scope the claim."
     "   * Inferences (X implies Y because of Z) must be labelled as"
     "     inference, not asserted as fact."
     "   * Sentiment hedging: if the source is uncertain, the writeup"
     "     stays uncertain. Don't flatten 'analysts disagree' into 'analysts say'."
     ""
     "4. Recency"
     "   * Content matches the time window the brief asked for. 'Current"
     "     market conditions' relying on a 3-year-old article is a violation"
     "     when the brief asks about today."
     "   * Sources should have a visible date. If a date is missing, flag"
     "     the source rather than silently treating it as recent."
     ""
     "5. Coverage"
     "   * Every sub-question in the brief got either a direct answer or a"
     "     documented gap. Skipping a hard sub-question silently is a"
     "     violation; explicitly noting 'insufficient public information"
     "     on X' is the correct behaviour."
     "   * Answers stay scoped to what the brief actually asked, not the"
     "     adjacent topics that are easier to find material on."
     ""
     "6. Scope discipline"
     "   * Agent stayed on the brief; did not drift into related topics"
     "     it wasn't asked about."
     "   * Output length is proportional to the brief — no padding with"
     "     tangential context to look thorough."
     ""
     "## Scoring"
     "- score: 0..10 (10 = ready to ship; ≤4 = rework required)."
     "- violations: each has file (the produced markdown/output file, or"
     "  \"\" if cross-cutting), type, severity (low | medium | high), and a"
     "  one-sentence reason. Allowed types:"
     "    unsourced-claim, fabricated-source, fabricated-quote,"
     "    over-extrapolation, single-source, stale-source, missing-date,"
     "    coverage-gap, scope-drift, padding, other."
     "- recommendation: continue | restore | needs-review."
     "- summary: one sentence on whether the research is trustworthy."
     ""
     "Treat unsourced-claim, fabricated-source, fabricated-quote, and"
     "over-extrapolation as HIGH severity by default — those are research"
     "integrity failures, not stylistic issues. The downstream consumer of"
     "this output may quote it; bad citations there are worse than missing"
     "ones here."]))

(def ^:private mode-config
  {:code     {:system "You are a strict code reviewer watching an autonomous coding agent. Score this iteration's output and flag any violations of the scope or constraints the agent was told to respect."
              :rubric code-rubric
              :max-diff-chars 20000}
   :spec     {:system "You are a strict reviewer for automatically-generated Wiggum implementation specs. Read the produced EDN file (visible in the diff below) and flag every gap that would cause the downstream Wiggum loop to misbehave."
              :rubric spec-rubric
              :max-diff-chars 80000}
   :research {:system "You are a strict reviewer for an autonomous research agent. The agent's deliverable is research output (notes, summaries, citation lists, analysis) — not source code. Score it for the failure modes that destroy research integrity: hallucinated sources, unsupported claims, single-source narratives, stale material, scope drift, fabricated quotes."
              :rubric research-rubric
              :max-diff-chars 80000}})

(def ^:private output-example
  (str/join "\n"
    ["## Output — JSON only, no markdown fences, no prose"
     "{"
     "  \"score\": 7,"
     "  \"violations\": ["
     "    {\"file\": \"output/foo.edn\", \"type\": \"missing-key\","
     "     \"severity\": \"high\", \"reason\": \"COMPLEX spec missing :acceptance-criteria\"}"
     "  ],"
     "  \"recommendation\": \"needs-review\","
     "  \"summary\": \"Spec is incomplete — :acceptance-criteria missing for COMPLEX run.\""
     "}"]))

(defn- review-prompt
  [{:keys [mode objective expected-files expected-check constraints anti-goals
           files-written files-edited files-deleted diff success-check]}]
  (let [{:keys [system rubric max-diff-chars]}
        (get mode-config (or mode :code) (:code mode-config))
        present-set     (set (:present expected-check))
        missing-set     (set (:missing expected-check))
        touched-set     (into #{} (concat files-written files-edited))
        ;; Files satisfied by earlier iterations — present now but not touched
        ;; this iteration. The agent is NOT required to recreate them.
        carried-over    (sort (remove touched-set present-set))
        produced-now    (sort (filter touched-set present-set))]
    (->> [system

          (str "## Overall objective\n" objective)

          (when success-check
            (str "## Overall success check (do not hold the agent to this for one iteration)\n`"
                 success-check "`"))

          (str "## Expected files for THIS iteration\n"
               (if (seq expected-files)
                 (str/join "\n" (map #(str "- " %) expected-files))
                 "(no expected-files declared — judge leniently)"))

          (when expected-check
            (str "## Expected-files status (after this iteration)\n"
                 (when (seq carried-over)
                   (str "Already present from prior iterations (NOT a violation"
                        " — agent is not required to recreate these):\n"
                        (str/join "\n" (map #(str "  ✓ " %) carried-over))
                        "\n"))
                 (when (seq produced-now)
                   (str "Produced or touched this iteration:\n"
                        (str/join "\n" (map #(str "  ✓ " %) produced-now))
                        "\n"))
                 (when (seq missing-set)
                   (str "MISSING (not present anywhere — flag as violation if"
                        " the agent should have produced them):\n"
                        (str/join "\n" (map #(str "  ✗ " %) (sort missing-set)))
                        "\n"))
                 "\nIMPORTANT: An empty diff or zero files-written this"
                 " iteration is NOT automatically a failure. If every expected"
                 " file is already present from a previous iteration, the"
                 " agent was correctly told there was nothing to do. Score"
                 " based on whether the cumulative state satisfies the"
                 " objective — not on whether THIS specific iteration wrote"
                 " bytes."))

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

          rubric

          output-example]
         (remove nil?)
         (str/join "\n\n"))))

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
              {:mode      (or (:mode ctx) :code)
               :expected  (count (:expected-files ctx))
               :written   (count (:files-written ctx))
               :edited    (count (:files-edited ctx))
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
