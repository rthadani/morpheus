(ns morpheus.executor.supervisor
  "Supervisor — reviews iteration evidence and emits the next control packet.

   Control packet:
     {:objective     string   — what to ship this iteration
      :constraints   [string] — hard limits on approach (≤3)
      :success-check string   — shell command that must exit 0 to count as done
      :anti-goals    [string] — things the executor must not do (≤3)
      :brief         string   — 2-3 sentence summary of the pivot

   The supervisor identifies the next bottleneck, detects drift from the goal,
   rejects low-value abstraction work, and escalates on regression."
  (:require [clojure.string          :as str]
            [taoensso.timbre         :as log]
            [morpheus.executor.evidence :as evidence]
            [morpheus.executor.llm   :as llm]))

(def default-model-config
  {:model-id "claude-haiku-4-5-20251001"
   :system   nil})

(def ^:private system-prompt
  "You are a delivery supervisor. An autonomous executor has just completed an
iteration of work toward a goal. Your job is to review the evidence and emit a
control packet that directs the next iteration.

The executor may be building software, writing documentation, processing data,
running experiments, generating reports, or any other kind of work. Reason about
progress in terms of the stated goal, not assumptions about the domain.

## Assessing progress

The final success check verifies the entire goal is met. It will keep failing
until all parts are done — that is expected. Your job each iteration is to
determine how far along the work is and what remains.

To assess progress:
1. Check the expected_files report — these are the specific outputs the previous
   iteration was supposed to produce. Present = that part is done. Missing = not done.
2. Check the directory tree for any additional context about what exists.
3. Read the verification failure output to understand which part of the goal is
   still unmet.
4. Cross-reference (1), (2), (3) against the original goal to find the earliest
   incomplete part.

Never direct the executor to redo work that the evidence shows is already present
and passing. Always advance to the next incomplete part of the goal.

## Judge review

When the evidence contains a `Judge review` block, treat it as a critical input.
The judge has inspected the diff against the prior accepted state and flagged
concrete violations.

- HIGH violations: the next packet MUST address them. Add the relevant rule to
  `constraints` or `anti_goals`, narrow `expected_files` to exclude the
  out-of-scope path, or instruct the executor to revert the offending change.
  Do not advance the objective until the violation is resolved.
- medium violations: fold into the next packet's constraints/anti_goals so the
  executor doesn't repeat the same mistake. Do not block progress on them
  unless they compound across iterations.
- low violations: note in the brief; act only if a pattern emerges.
- recommendation `restore`: the iteration was rolled back. Re-emit the same
  phase's objective with tighter scoping that prevents the failure mode the
  judge identified.

The judge's `summary` is a one-sentence quality call — use it as a sanity
check on your own read of the iteration.

## What you must do
- Identify the single most important thing to unblock next.
- Detect drift from the original goal and redirect toward it.
- Reject work that does not move the goal forward: indirection, abstraction,
  refactoring, or cleanup that is not required by the goal.
- Escalate clearly if verification failed, regressed, or was skipped.
- Declare expected_files for this iteration so progress can be measured precisely.

## Phased objectives

When the original goal describes sequential phases (e.g. labelled sections like
\"Phase 1\", \"=== Phase 2 ===\", \"Step 1:\", or numbered stages), treat each
phase as a hard boundary:

- Scope the control packet's objective to the **earliest incomplete phase only**.
  Do not mention, reference, or instruct the executor to begin any later phase.
- A phase is complete when the evidence contains the line
  `*** PHASE COMPLETE ***`. When you see that line, you MUST advance to the
  next phase immediately — do not re-assign the same phase, do not add extra
  constraints to \"tighten\" it, do not ask for tests that belong to a later phase.
- If the phase-check line is absent or says INCOMPLETE, keep the same phase
  objective and adjust constraints/anti_goals to unblock the executor.
- The overall verification (success-check) will keep failing until all phases
  are done — ignore its failure as a signal about the current phase.
  Use only the `*** PHASE COMPLETE ***` / INCOMPLETE line for phase gating.
- Add an anti_goal entry explicitly forbidding the executor from starting later
  phases: e.g. \"Do not start the backend — focus only on the React frontend.\"

This ensures a human reviewer sees exactly one phase of work per iteration
rather than the executor racing through multiple phases at once.

## What you must not do
- Change the success check — it is owned by the product owner, not you.
- Decompose work into parallel sub-agents or a DAG.
- Micromanage implementation details.
- Prevent the executor from completing later parts of the goal by constraining it
  to earlier parts that are already done.

## Output format
Respond with a JSON object and nothing else. No preamble, no markdown fences,
no trailing prose, no `//` or `/* */` comments inside the JSON, no fields
beyond those listed below. Output tokens are billed against the user's quota,
so be ruthless about omitting anything that does not change what the executor
will do.

{
  \"objective\":      \"<one sentence: the concrete deliverable for this iteration>\",
  \"constraints\":    [\"<hard limit on approach>\", ...],
  \"anti_goals\":     [\"<output or action to avoid>\", ...],
  \"brief\":          \"<at most one short sentence>\",
  \"plan\":           [\"<step 1>\", \"<step 2>\", ...],
  \"expected_files\": [\"<path/to/file>\", \"<dir/>\", ...]
}

Field rules:

- objective: required. One sentence, no rationale, no examples.
- expected_files: required. Paths relative to the project root, trailing slash
  for directories. Include every deliverable type — source, tests, config,
  docs. Paths only, no commentary inside the array.
- constraints, anti_goals: at most 3 items each. Each item is a single rule,
  not a paragraph. The control packet REPLACES the executor's CLAUDE.md
  wholesale — there is no merge with the prior packet. Any rule that should
  remain in force MUST be re-listed; rules you omit are dropped. With the ≤3
  cap, choose the most load-bearing rules for this iteration (e.g. a
  phase-boundary anti_goal must persist until the phase is done). Omit the
  field only when the iteration genuinely has no rules to enforce.
- brief: omit by default. Include only when the iteration changes direction
  (pivot, regression, restore) and the executor needs to know why. Even then,
  one short sentence — not a summary of the evidence.
- plan: omit by default. Include only on the first iteration, after a
  verification failure, or when pivoting. One terse imperative line per step,
  no introductions, no trailing notes.

Never decorate the JSON with explanations, status headers, or recap text.
The control packet is fed verbatim into the next iteration's CLAUDE.md, so
every extra word costs the user twice — once on output here, once on input
to the next iteration.")

(defn- format-control-packet [packet]
  (str/join "\n"
    (cond-> ["Current control packet:"
             (str "  objective:      " (:objective packet))
             (str "  constraints:    " (str/join ", " (:constraints packet)))
             (str "  success-check:  " (:success-check packet))
             (str "  anti-goals:     " (str/join ", " (:anti-goals packet)))]
      (seq (:expected-files packet))
      (conj (str "  expected-files: " (str/join ", " (:expected-files packet)))))))

(defn- needs-output-tail?
  "Successful iterations are fully described by the structured summary
   (files written/edited, verification status, expected_files check, judge
   review). Only include CC's raw stdout when something the summary doesn't
   already explain may be hiding in it: non-zero CC exit, failed verification,
   or token exhaustion."
  [ev]
  (or (pos? (:exit-code ev 0))
      (when-let [v (:verification ev)] (pos? (:exit v 0)))
      (:exhausted? ev)))

(defn- format-evidence-block [evidence-list]
  (if (empty? evidence-list)
    "No iterations have run yet."
    (str/join "\n\n"
      (map (fn [ev]
             (str "---\n" (evidence/summarise ev)
                  ;; Tail (not head): the actual conclusion or final error
                  ;; lives at the end of CC's transcript; the head is intro
                  ;; chatter. Capped tight to keep supervisor input cheap.
                  (when (and (needs-output-tail? ev) (seq (:output ev)))
                    (let [trimmed (str/trim (:output ev))
                          tail    (if (> (count trimmed) 600)
                                    (str "… (earlier output omitted)\n"
                                         (subs trimmed (- (count trimmed) 600)))
                                    trimmed)]
                      (str "\n  Output tail:\n"
                           (str/join "\n" (map #(str "    " %)
                                               (str/split-lines tail))))))))
           evidence-list))))

(defn- build-prompt
  ([run-objective current-packet evidence-list]
   (build-prompt run-objective current-packet evidence-list nil nil))
  ([run-objective current-packet evidence-list user-feedback]
   (build-prompt run-objective current-packet evidence-list user-feedback nil))
  ([run-objective current-packet evidence-list user-feedback initial-state]
   (str/join "\n\n"
     (cond-> ["## Original product goal"
              run-objective]
       (seq initial-state)
       (conj (str "## Pre-existing state (present before any iteration ran)\n"
                  initial-state "\n\n"
                  "Use this to determine which parts of the goal are already complete "
                  "so you do not direct the executor to rebuild them.\n\n"
                  "IMPORTANT: The executor runs inside the project root directory. "
                  "Do not reference the project name as a path prefix in your plan "
                  "(e.g. do not write `kanban-full/src/` — just write `src/`)."))
       :always
       (conj "## Last iteration evidence"
             (format-evidence-block evidence-list)
             "## Current control packet (what the executor was last told)"
             (format-control-packet current-packet))
       (seq user-feedback)
       (conj (str "## Human reviewer feedback\n"
                  user-feedback "\n\n"
                  "IMPORTANT: The human's feedback above is the ONLY guidance to act on "
                  "for this iteration. It overrides both your own assessment AND the "
                  "default rules in the system prompt about how to handle judge "
                  "violations.\n\n"
                  "Do NOT automatically fold every judge violation into the next "
                  "control packet. The judge's violation list is reference material "
                  "the human used to decide what to ask for — treat it as context, "
                  "not as a worklist. Address only the violations the human's "
                  "feedback names or clearly implies (e.g. \"fix the 1st and 2nd\", "
                  "\"address the medium ones\", \"ignore the auth issue\"). Leave any "
                  "violation the human did not reference untouched for this "
                  "iteration — it can be revisited later.\n\n"
                  "If the human's feedback is broad or doesn't reference the judge "
                  "(e.g. \"focus on tests\", \"continue\"), follow it directly and "
                  "fall back to the default judge-handling rules only for "
                  "violations that don't conflict with their direction."))
       :always
       (conj "## Your task"
             (str "Review the evidence above. Determine whether the executor is on track "
                  "toward the original goal or has drifted. Emit the next control packet."))))))

(defn- normalise [raw]
  (cond-> {:objective      (or (:objective raw) "Continue toward the product goal.")
           :constraints    (vec (or (:constraints raw) []))
           :success-check  (or (:success_check raw) (:success-check raw) "echo ok")
           :anti-goals     (vec (or (:anti_goals raw) (:anti-goals raw) []))
           :brief          (or (:brief raw) "")}
    (seq (:plan raw))           (assoc :plan           (vec (:plan raw)))
    (seq (:expected_files raw)) (assoc :expected-files (vec (:expected_files raw)))))

(defn review
  "Reviews recent evidence and returns the next control packet.
   evidence-list should be the last 3-5 iterations to bound prompt size."
  ([run-objective current-packet evidence-list]
   (review run-objective current-packet evidence-list {}))
  ([run-objective current-packet evidence-list model-config]
   (review run-objective current-packet evidence-list model-config nil))
  ([run-objective current-packet evidence-list model-config user-feedback]
   (review run-objective current-packet evidence-list model-config user-feedback nil))
  ([run-objective current-packet evidence-list model-config user-feedback initial-state]
   (log/info "Supervisor reviewing" {:iterations    (count evidence-list)
                                     :has-feedback  (boolean (seq user-feedback))
                                     :has-initial   (boolean (seq initial-state))})
   (let [cfg    (merge default-model-config
                       {:system system-prompt}
                       model-config)
         prompt (build-prompt run-objective current-packet evidence-list user-feedback initial-state)
         raw    (llm/complete-json cfg prompt)
         packet (normalise raw)]
     (log/info "Supervisor emitted control packet"
               {:objective (subs (:objective packet) 0
                                 (min 80 (count (:objective packet))))})
     packet)))

(defn bootstrap
  "First control packet from a run-config. Called once before any iteration runs."
  [{:keys [objective success-check constraints anti-goals]
    :or   {success-check "echo ok"
           constraints   []
           anti-goals    []}}]
  {:objective     objective
   :constraints   (vec constraints)
   :success-check success-check
   :anti-goals    (vec anti-goals)
   :brief         "First iteration — no prior evidence."})

(def ^:private pivot-system-prompt
  "You are a debugging advisor reviewing a stuck autonomous executor.
The executor has failed the same verification check multiple times in a row.
Analyze the evidence and respond with 2-4 sentences:
1. The root cause — what specifically is failing and why (quote the actual error text if visible).
2. One concrete alternative approach to try next.
Be specific to the actual error output. Do not give generic advice like 'try TDD' or 'simplify'.
Do not repeat advice that earlier iterations have already tried.
Plain prose only — no JSON, no markdown, no bullet points.")

(defn- format-pivot-evidence [evidence-list]
  (str/join "\n\n"
    (map (fn [ev]
           (let [ver  (:verification ev)
                 tail (when (and ver (pos? (:exit ver 0)))
                        (some-> (:output ver) str/trim
                                (as-> s (if (> (count s) 400)
                                          (str "…" (subs s (- (count s) 400)))
                                          s))))
                 out  (when (pos? (:exit-code ev 0))
                        (let [o (str/trim (or (:output ev) ""))]
                          (when (seq o)
                            (if (> (count o) 400)
                              (str "…" (subs o (- (count o) 400)))
                              o))))]
             (str "--- iteration " (:iteration ev) " ---\n"
                  "exit=" (:exit-code ev) "  "
                  "files-written=" (count (:files-written ev)) "\n"
                  (when tail (str "verification failure:\n  " tail "\n"))
                  (when out  (str "executor output tail:\n  " out)))))
         evidence-list)))

(defn suggest-pivot!
  "Analyses recent stuck evidence and returns a short plain-prose suggestion
   for the supervisor's user-feedback slot.  Returns nil when the LLM call
   fails — callers must handle nil gracefully."
  [run-objective current-packet recent-evidence model-config]
  (let [n      (count recent-evidence)
        cfg    (merge default-model-config
                      {:system pivot-system-prompt}
                      model-config)
        prompt (str/join "\n\n"
                 ["## Goal"
                  run-objective
                  "## What the executor was last told"
                  (str "objective: " (:objective current-packet))
                  (when (seq (:expected-files current-packet))
                    (str "expected files: "
                         (str/join ", " (:expected-files current-packet))))
                  "## Recent failed iterations"
                  (format-pivot-evidence recent-evidence)
                  "## Your task"
                  (str "The executor has failed verification " n " time"
                       (when (> n 1) "s") " in a row.\n"
                       "Diagnose the root cause from the evidence above and "
                       "suggest one concrete alternative approach.")])]
    (log/info "Suggest-pivot calling LLM" {:iterations n})
    (llm/complete cfg prompt)))
