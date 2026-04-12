(ns morpheus.executor.supervisor
  "Supervisor — reviews iteration evidence and emits a control packet
   for the next iteration.

   A control packet is:
     {:objective     string   — what to ship this iteration (one sentence)
      :constraints   [string] — hard limits on approach (≤3)
      :success-check string   — shell command that must exit 0 to count as done
      :anti-goals    [string] — things the executor must not do (≤3)
      :brief         string   — supervisor's 2-3 sentence summary of why we're pivoting}

   The supervisor identifies the next bottleneck, detects drift from the product
   goal, rejects low-value abstraction work, and escalates on regression.
   It does not decompose work into a sub-agent DAG."
  (:require [clojure.string          :as str]
            [taoensso.timbre         :as log]
            [morpheus.executor.evidence :as evidence]
            [morpheus.executor.llm   :as llm]))

;; ──────────────────────────────────────────
;; Model config
;; ──────────────────────────────────────────

(def default-model-config
  {:model-id    "claude-haiku-4-5-20251001"
   :max-tokens  2048
   :temperature 0.2
   :system      nil})  ; overridden below

;; ──────────────────────────────────────────
;; Prompt construction
;; ──────────────────────────────────────────

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

## What you must do
- Identify the single most important thing to unblock next.
- Detect drift from the original goal and redirect toward it.
- Reject work that does not move the goal forward: indirection, abstraction,
  refactoring, or cleanup that is not required by the goal.
- Escalate clearly if verification failed, regressed, or was skipped.
- Declare expected_files for this iteration so progress can be measured precisely.

## What you must not do
- Change the success check — it is owned by the product owner, not you.
- Decompose work into parallel sub-agents or a DAG.
- Micromanage implementation details.
- Prevent the executor from completing later parts of the goal by constraining it
  to earlier parts that are already done.

## Output format
Respond with a JSON object and nothing else:
{
  \"objective\":      \"<one sentence: the concrete deliverable for this iteration>\",
  \"constraints\":    [\"<hard limit on approach>\", ...],
  \"anti_goals\":     [\"<output or action to avoid>\", ...],
  \"brief\":          \"<2-3 sentences: what the evidence shows and why you chose this direction>\",
  \"plan\":           [\"<step 1>\", \"<step 2>\", ...],
  \"expected_files\": [\"<path/to/file>\", \"<dir/>\", ...]
}

constraints and anti_goals: at most 3 items each.

plan: include only when the executor needs explicit direction — first iteration,
after a failure, or when pivoting to a new part of the goal. Steps must be
concrete enough to execute directly. Omit when the executor is already on track.

expected_files: required. List the specific files or directories that must exist
for this iteration to be considered complete. Use paths relative to the project
root. Use a trailing slash for directories (e.g. \"src/\"). Include every
deliverable type: source files, test files, config files, and documentation.
For a documentation phase, list the expected docs (e.g. \"README.md\",
\"CLAUDE.md\"). These are used to verify progress on the next review.")

(defn- format-control-packet [packet]
  (str/join "\n"
    (cond-> ["Current control packet:"
             (str "  objective:      " (:objective packet))
             (str "  constraints:    " (str/join ", " (:constraints packet)))
             (str "  success-check:  " (:success-check packet))
             (str "  anti-goals:     " (str/join ", " (:anti-goals packet)))]
      (seq (:expected-files packet))
      (conj (str "  expected-files: " (str/join ", " (:expected-files packet)))))))

(defn- format-evidence-block [evidence-list]
  (if (empty? evidence-list)
    "No iterations have run yet."
    (str/join "\n\n"
      (map (fn [ev]
             (str "---\n" (evidence/summarise ev)
                  (when-let [out (:output ev)]
                    (let [trimmed (str/trim out)
                          snip    (if (> (count trimmed) 2000)
                                    (str (subs trimmed 0 2000) "\n… (truncated)")
                                    trimmed)]
                      (str "\n  Output snippet:\n"
                           (str/join "\n" (map #(str "    " %) (str/split-lines snip))))))))
           evidence-list))))

(defn- build-prompt
  "Assembles the user-turn prompt for the supervisor LLM call.
   initial-state — optional string describing what was already in the work-dir
                   before any iteration ran (e.g. a pre-existing project).
                   Shown before evidence so the supervisor knows the starting point."
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
                  "IMPORTANT: The human has reviewed this iteration and left the above "
                  "feedback. Incorporate it directly into the next control packet. "
                  "Their steering overrides your own assessment."))
       :always
       (conj "## Your task"
             (str "Review the evidence above. Determine whether the executor is on track "
                  "toward the original goal or has drifted. Emit the next control packet."))))))

;; ──────────────────────────────────────────
;; Response normalisation
;; ──────────────────────────────────────────

(defn- normalise
  "Converts raw JSON-parsed map (keyword keys, underscore names) to internal shape."
  [raw]
  (cond-> {:objective      (or (:objective raw) "Continue toward the product goal.")
           :constraints    (vec (or (:constraints raw) []))
           :success-check  (or (:success_check raw) (:success-check raw) "echo ok")
           :anti-goals     (vec (or (:anti_goals raw) (:anti-goals raw) []))
           :brief          (or (:brief raw) "")}
    (seq (:plan raw))           (assoc :plan           (vec (:plan raw)))
    (seq (:expected_files raw)) (assoc :expected-files (vec (:expected_files raw)))))

;; ──────────────────────────────────────────
;; Public API
;; ──────────────────────────────────────────

(defn review
  "Reviews the last N iterations of evidence and returns a control packet
   for the next iteration.

   run-objective  — the original high-level product goal (string).
                    Kept stable across all iterations so the supervisor
                    can detect drift.
   current-packet — the control packet used for the most recent iteration.
   evidence-list  — vec of evidence maps in chronological order (from evidence/build).
                    Pass the last 3-5 for context without ballooning the prompt.
   model-config   — optional map of LLM overrides (merges with default-model-config)."
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
  "Builds the very first control packet from a run config map.
   Called once before any iteration runs — no evidence to review.

   run-config keys:
     :objective     required — the product goal
     :success-check optional — default \"echo ok\"
     :constraints   optional — initial hard limits
     :anti-goals    optional — initial anti-goals"
  [{:keys [objective success-check constraints anti-goals]
    :or   {success-check "echo ok"
           constraints   []
           anti-goals    []}}]
  {:objective     objective
   :constraints   (vec constraints)
   :success-check success-check
   :anti-goals    (vec anti-goals)
   :brief         "First iteration — no prior evidence."})
