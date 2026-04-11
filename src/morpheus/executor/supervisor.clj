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
  {:model-id    "claude-sonnet-4-6"
   :max-tokens  2048
   :temperature 0.2
   :system      nil})  ; overridden below

;; ──────────────────────────────────────────
;; Prompt construction
;; ──────────────────────────────────────────

(def ^:private system-prompt
  "You are a software delivery supervisor. An autonomous executor has just
completed an iteration. Your job is to review the evidence and emit a tight
control packet that directs the next iteration.

## What you must do
- Identify the single most important bottleneck to unblock next.
- Detect drift from the original product goal and redirect toward it.
- Reject low-value abstraction work: new wrappers, helper files, config layers,
  indirection that isn't immediately used.
- Escalate if verification failed, regressed, or was skipped.
- Compress the evidence into a short brief the executor can act on.

## What you must not do
- Decompose the work into a DAG of sub-tasks or multiple parallel agents.
- Micromanage implementation details (file names, function signatures, etc.).
- Reward code volume over visible product progress.
- Ask the executor to coordinate with other agents.

## Output format
Respond with a JSON object and nothing else:
{
  \"objective\":     \"<one sentence: what to ship this iteration>\",
  \"constraints\":   [\"<hard limit>\", ...],
  \"success_check\": \"<shell command that must exit 0>\",
  \"anti_goals\":    [\"<thing to avoid>\", ...],
  \"brief\":         \"<2-3 sentences: what happened and why we're pivoting>\"
}

Constraints and anti_goals must each have at most 3 items.")

(defn- format-control-packet [packet]
  (str/join "\n"
    ["Current control packet:"
     (str "  objective:     " (:objective packet))
     (str "  constraints:   " (str/join ", " (:constraints packet)))
     (str "  success-check: " (:success-check packet))
     (str "  anti-goals:    " (str/join ", " (:anti-goals packet)))]))

(defn- format-evidence-block [evidence-list]
  (if (empty? evidence-list)
    "No iterations have run yet."
    (str/join "\n\n"
      (map (fn [ev]
             (str "---\n" (evidence/summarise ev)
                  (when-let [out (:output ev)]
                    (let [trimmed (str/trim out)
                          snip    (if (> (count trimmed) 600)
                                    (str (subs trimmed 0 600) "\n… (truncated)")
                                    trimmed)]
                      (str "\n  Output snippet:\n"
                           (str/join "\n" (map #(str "    " %) (str/split-lines snip))))))))
           evidence-list))))

(defn- build-prompt
  "Assembles the user-turn prompt for the supervisor LLM call."
  ([run-objective current-packet evidence-list]
   (build-prompt run-objective current-packet evidence-list nil))
  ([run-objective current-packet evidence-list user-feedback]
   (str/join "\n\n"
     (cond-> ["## Original product goal"
              run-objective
              "## Last iteration evidence"
              (format-evidence-block evidence-list)
              "## Current control packet (what the executor was last told)"
              (format-control-packet current-packet)]
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
  {:objective     (or (:objective raw) "Continue toward the product goal.")
   :constraints   (vec (or (:constraints raw) []))
   :success-check (or (:success_check raw) (:success-check raw) "echo ok")
   :anti-goals    (vec (or (:anti_goals raw) (:anti-goals raw) []))
   :brief         (or (:brief raw) "")})

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
   (log/info "Supervisor reviewing" {:iterations (count evidence-list)
                                     :has-feedback (boolean (seq user-feedback))})
   (let [cfg    (merge default-model-config
                       {:system system-prompt}
                       model-config)
         prompt (build-prompt run-objective current-packet evidence-list user-feedback)
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
