(ns morpheus.graphs.expanders
  "Graph expansion functions. Called by :graph-expand nodes.
   Pure functions — take inputs map, return seq of node definitions.
   Each task node gets a :claude-md that scopes Claude Code to its job.")

;; ──────────────────────────────────────────
;; CLAUDE.md generators — one per task type
;; ──────────────────────────────────────────

(defn arch-claude-md [milestone-id brief project-dir]
  (str "# Task: architecture design — " (name milestone-id) "\n\n"
       "## Your job\n"
       "Design the architecture for this milestone. "
       "Read the existing codebase to understand conventions before proposing anything.\n\n"
       "## Milestone brief\n"
       brief "\n\n"
       "## Deliverable\n"
       "Write an ADR to `docs/adr/" (name milestone-id) ".md`.\n"
       "Cover: namespaces, protocols/interfaces, data shapes, external dependencies.\n\n"
       "## Constraints\n"
       "- Match existing namespace and naming conventions\n"
       "- No new dependencies without justification\n"
       "- Prefer data over objects\n\n"
       "## Done when\n"
       "`docs/adr/" (name milestone-id) ".md` exists and covers all required sections.\n"
       (when project-dir
         (str "\n## Codebase\nThe project is in `" project-dir "`. Read it before designing.\n"))))

(defn implement-claude-md [milestone-id brief project-dir]
  (str "# Task: implementation — " (name milestone-id) "\n\n"
       "## Your job\n"
       "Implement the code described in the ADR for this milestone.\n\n"
       "## Milestone brief\n"
       brief "\n\n"
       "## Inputs\n"
       "- ADR: `docs/adr/" (name milestone-id) ".md` — read this first\n"
       (when project-dir (str "- Existing codebase: `" project-dir "`\n"))
       "\n## Deliverable\n"
       "Write all implementation files to `src/`. "
       "Follow the namespace structure in the ADR exactly.\n\n"
       "## Constraints\n"
       "- All public functions must have docstrings\n"
       "- No stubs — implement fully\n"
       "- Match existing code style\n\n"
       "## Done when\n"
       "All namespaces in the ADR exist and are fully implemented.\n"))

(defn tests-claude-md [milestone-id project-dir]
  (str "# Task: tests — " (name milestone-id) "\n\n"
       "## Your job\n"
       "Write clojure.test tests for the implementation of this milestone.\n\n"
       "## Inputs\n"
       "- Implementation: `src/` — read it first\n"
       "- ADR: `docs/adr/" (name milestone-id) ".md`\n\n"
       "## Deliverable\n"
       "Write test files to `test/`. Mirror the src namespace structure.\n\n"
       "## Constraints\n"
       "- Cover happy paths and at least two edge cases per public function\n"
       "- Tests must pass: run `clj -M:test` to verify\n\n"
       "## Done when\n"
       "`clj -M:test` exits 0.\n"))

(defn integrate-claude-md [milestone-id project-dir]
  (str "# Task: integration review — " (name milestone-id) "\n\n"
       "## Your job\n"
       "Review integration points between this milestone and prior milestones. "
       "Fix any interface mismatches or broken calls.\n\n"
       "## Inputs\n"
       "- This milestone implementation: `src/`\n"
       "- Prior context summary: provided via prompt\n\n"
       "## Deliverable\n"
       "Fix issues in-place. Write summary to `docs/integration/" (name milestone-id) ".md`.\n\n"
       "## Done when\n"
       "`clj -M:test` exits 0 and integration doc exists.\n"))

;; ──────────────────────────────────────────
;; Milestone subgraph
;; ──────────────────────────────────────────

(defn milestone-subgraph
  "Returns the inner graph for a single milestone.
   Each task node carries a :claude-md scoped to its job."
  [milestone-id brief project-dir]
  {:graph/id    milestone-id
   :graph/nodes
   [{:id          :arch
     :type        :task
     :depends-on  []
     :inputs      {:brief [:milestone-brief]
                   :prior [:context-store/summary]}
     :claude-md   (arch-claude-md milestone-id brief project-dir)
     :project-dir project-dir
     :prompt      "Design the architecture for this milestone.\n\nBrief:\n{{brief}}\n\nPrior context:\n{{prior}}"
     :output-key  :arch/output}

    {:id          :implement
     :type        :task
     :depends-on  [:arch]
     :inputs      {:arch  [:arch/output]
                   :brief [:milestone-brief]}
     :claude-md   (implement-claude-md milestone-id brief project-dir)
     :project-dir project-dir
     :prompt      "Implement this milestone.\n\nADR:\n{{arch}}\n\nBrief:\n{{brief}}"
     :output-key  :implement/output}

    {:id          :tests
     :type        :task
     :depends-on  [:implement]
     :inputs      {:impl [:implement/output]}
     :claude-md   (tests-claude-md milestone-id project-dir)
     :project-dir project-dir
     :prompt      "Write tests for the implementation.\n\n{{impl}}"
     :done-check  "clj -M:test"
     :output-key  :tests/output}

    {:id          :integrate
     :type        :task
     :depends-on  [:tests]
     :inputs      {:impl  [:implement/output]
                   :prior [:context-store/summary]}
     :claude-md   (integrate-claude-md milestone-id project-dir)
     :project-dir project-dir
     :prompt      "Review and fix integration points.\n\nThis milestone:\n{{impl}}\n\nPrior:\n{{prior}}"
     :output-key  :integrate/output}]})

;; ──────────────────────────────────────────
;; Expander — called by :graph-expand node
;; ──────────────────────────────────────────

(defn milestones->nodes
  "Expands planning sections into sequential subgraph + checkpoint pairs.

   inputs map:
     :plan        — planning node output {:sections {kw text} ...}
     :project-dir — optional path to real codebase (forwarded to all task nodes)"
  [{:keys [plan project-dir]} _context]
  (let [sections (:sections plan)]
    (:nodes
      (reduce
        (fn [{:keys [nodes prior-review]} [milestone-id brief]]
          (let [slug      (name milestone-id)
                sg-id     (keyword (str slug "/subgraph"))
                review-id (keyword (str slug "/review"))]
            {:nodes
             (into nodes
                   [{:id          sg-id
                     :type        :subgraph
                     :depends-on  (if prior-review [prior-review] [:expand-milestones])
                     :graph       (milestone-subgraph milestone-id brief project-dir)
                     :inputs      {:milestone-brief brief
                                   :prior-context   [:context-store/summary]}
                     :output-key  milestone-id}

                    {:id         review-id
                     :type       :checkpoint
                     :depends-on [sg-id]
                     :present    [milestone-id]
                     :actions    [:approve :revise :abort]
                     :on-revise  {:re-run     sg-id
                                  :inject-key (keyword slug "feedback")}
                     :output-key (keyword slug "decision")}])
             :prior-review review-id}))
        {:nodes [] :prior-review nil}
        sections))))
