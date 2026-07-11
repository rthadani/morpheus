# Morpheus — reference

## Wiggum config (full key reference)

```clojure
{;; Core
 :objective          "Build a working X that does Y and Z."
 :project-dir        nil             ; copied into work-dir at start; set at runtime
 :success-check      "npm test"      ; shell command; exit 0 = done. Default "echo ok"
 :max-iterations     20              ; hard cap (default 20)
 :timeout-ms         300000          ; per-iteration agent timeout (default 5 min)

 ;; Phase constraints (initial values for the first control packet)
 :constraints        ["Write tests alongside implementation"]
 :anti-goals         ["Do not add dependencies that aren't immediately used"]

 ;; Per-phase acceptance gates (MEDIUM/COMPLEX specs)
 :acceptance-criteria nil

 ;; Model selection
 :model-config            nil   ; LLM for both executor and supervisor
 :executor-model-config   nil   ; overrides executor only
 :supervisor-model-config nil   ; overrides supervisor only
 :fallback-model          nil   ; model-id to retry on Anthropic rate-limit (claude agent only)
 :fallback-delay-ms       30000

 ;; Review / judge
 :judge-mode       :code   ; :code | :spec | :research
 :review-threshold :high   ; :none | :low | :medium | :high

 ;; Run behaviour
 :step-once?          false
 :checkpoint-every    nil
 :generate-claude-md? true
 :polish-pass?        false

 ;; Auto-steer (stuck-loop recovery)
 :auto-steer?      false
 :stuck-threshold  3}
```

## Agents and providers

`:model-config` (or the per-side overrides) controls two axes:
- **`:agent`** — which CLI: `:claude` (default) or `:pi`
- **`:provider`** — which backend within that agent

### Claude agent

| Provider  | Backend                              | Required env        |
|-----------|--------------------------------------|---------------------|
| (default) | `claude --print` → api.anthropic.com | `ANTHROPIC_API_KEY` |
| `:kimi`   | `claude --print` → api.moonshot.ai   | `MOONSHOT_API_KEY`  |
| `:ollama` | local Ollama server                  | none                |

`:minimax` is supported for the supervisor (direct LLM calls) but not for the claude executor.

### Pi agent

```bash
npm install -g @earendil-works/pi-coding-agent
```

| `:provider` | Backend     | Required env        |
|-------------|-------------|---------------------|
| `:kimi`     | Moonshot AI | `MOONSHOT_API_KEY`  |
| `:minimax`  | MiniMax     | `MINIMAX_API_KEY`   |
| `:anthropic`| Anthropic   | `ANTHROPIC_API_KEY` |
| `:openai`   | OpenAI      | `OPENAI_API_KEY`    |
| `:google`   | Google      | `GOOGLE_API_KEY`    |
| `:ollama`   | local Ollama| none                |

### Examples

```clojure
;; Ollama executor, Anthropic supervisor
{:executor-model-config   {:provider :ollama :model-id "qwen2.5-coder:32b"}
 :supervisor-model-config {:model-id "claude-sonnet-4-6"}}

;; Kimi executor (via claude), Anthropic supervisor
{:executor-model-config   {:provider :kimi :model-id "kimi-k2.5"}
 :supervisor-model-config {:model-id "claude-sonnet-4-6"}}

;; Pi executor (Kimi), Pi supervisor (MiniMax)
{:executor-model-config   {:agent :pi :provider :kimi    :model-id "kimi-k2.6"}
 :supervisor-model-config {:agent :pi :provider :minimax :model-id "MiniMax-M2.7"}}
```

Rate-limit fallback (`:fallback-model`) applies to the `:claude` agent only. A rate-limited pi run surfaces as an exhaustion pause.

## The judge and review panel

Three things can pause a Wiggum run:

- **Judge pause** — at the end of every phase, an LLM judge inspects the git diff, scores 0–10, and emits violations tagged `high` / `medium` / `low`. If any violation meets `:review-threshold` (default `:high`), the run pauses.
- **Milestone pause** — every `:checkpoint-every` iterations, regardless of the judge.
- **Step pause** — after every iteration when `:step-once?` is true or you toggle **Step ON** in the UI.

| Button | When | What it does |
|--------|------|--------------|
| **Continue** | always | Accept and move on. Text in the textarea becomes `## Human reviewer feedback` in the next control packet. |
| **Retry** | always | Re-run the same iteration with the same control packet. |
| **Restore** | judge review present | `git restore` to pre-iteration state and re-enter the phase. |
| **Ignore judge** | judge review present | Drop the judge's review from evidence; the supervisor won't see it. |
| **Abort** | always | Stop the run. |

**Feedback semantics:**
- **Empty + Continue** — supervisor follows default judge rules: HIGH violations must be addressed, medium become constraints, low are noted on patterns.
- **Targeted** (`"fix the 1st and 2nd"`) — supervisor treats only your instruction as the worklist.
- **Broad** (`"focus on tests"`) — supervisor follows your direction and handles the rest by default.
- **At a verified iteration** — normally terminates; with feedback, runs one more iteration.
- **At `:max-iterations`** — cap is bumped by 1 so the feedback isn't dropped.

## Auto-steer

When `:auto-steer? true`, after `:stuck-threshold` (default 3) consecutive verify failures Morpheus calls an LLM to analyse the evidence (verification output, files tried) and injects a context-specific pivot suggestion into the next supervisor call. Works with both claude and pi executors.

## DAG executor

For when you want explicit control over decomposition and sequencing.

### Node types

| Type           | Executor         | Use for                              |
|----------------|------------------|--------------------------------------|
| `:task`        | claude or pi CLI | Any unit of work needing file tools  |
| `:planning`    | claude or pi CLI | PRD → milestone sections             |
| `:parallel`    | claude or pi CLI | N concurrent agent sessions          |
| `:checkpoint`  | none             | Human review gate                    |
| `:graph-expand`| pure fn          | Splice new nodes into the live graph |
| `:subgraph`    | recursive engine | Run a nested graph                   |
| `:wiggum`      | wiggum loop      | Embed a full Wiggum loop as a node   |
| `:shell`       | sh subprocess    | Run tests, build commands            |
| `:http`        | http-kit         | Webhooks, external APIs              |

### Node config

```clojure
{:id          :my-node
 :type        :task
 :depends-on  [:prior-node]
 :project-dir [:graph/params :project-dir]
 :claude-md   "# Task: my-node\n## Your job\n..."
 :prompt      "Do X at {{project-dir}}."
 :inputs      {:project-dir [:graph/params :project-dir]}
 :done-check  "clj -M:test"
 :output-key  :my-node/output}
```

### REPL

```clojure
(require '[morpheus.system :as sys])
(sys/go!)   ; HTTP server on port 7777

(require '[clojure.edn :as edn]
         '[morpheus.executor.engine :as engine]
         '[morpheus.executor.wiggum :as wiggum])

;; DAG run
(def graph (edn/read-string (slurp "graphs/examples/todo-app-dag.edn")))
(def run   (engine/execute! 1 graph {:graph/params {:project-dir "/tmp/todo"}}))
@(:state run)

;; Wiggum run
(def cfg (edn/read-string (slurp "graphs/examples/todo-app-wiggum.edn")))
(def run (wiggum/execute! 1 (assoc cfg :project-dir "/tmp/todo")))
@(:iterations run)

;; Resume a checkpoint
(engine/resume! run {:action :approve :node-id :review})
(engine/resume! run {:action :revise  :node-id :review :feedback "add pagination"})
```

## Extending

**New node type:** add keyword to `graph/schema.clj`, add `defmethod execute-node!` in `executor/dispatch.clj`, add hiccup rendering in `ui/components.clj`.

**New CLAUDE.md template:** add a function to `graphs/expanders.clj` taking `[milestone-id brief project-dir]` and returning a string.

**New graph:** create an EDN file in `graphs/`. No code registration needed.
