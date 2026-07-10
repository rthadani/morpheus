# Morpheus

> Aboard the Nebuchadnezzar, Morpheus reads the screens. The crew below jacks
> into the Matrix one by one — each given a fragment of the dream to shape,
> each watched, each pulled out when their slice of the world is done. Between
> insertions Morpheus studies what came back, adjusts the next briefing, and
> sends the next operator under. Nothing happens that he didn't dispatch;
> nothing returns that he doesn't read.
>
> Or, if you prefer the older story: Morpheus, son of Hypnos, shaper of
> dreams. He doesn't appear in your sleep himself — he sends the figures who
> do, decides what each will say, and listens for the dreamer's reaction
> before sending the next.

Less poetically: **Morpheus is an agent orchestration system where coding
agents are the executors.** You describe work as data (EDN); Morpheus
launches `claude` or `pi` CLI subprocesses to do it; a supervisor LLM reads
the evidence each one brings back and steers the next iteration.

## How it works

Morpheus has two execution models:

### DAG executor

You define a graph of task nodes with explicit dependencies. Morpheus walks the graph topologically, running a scoped agent session per node (claude or pi, per the node's `:executor` / `:model-config`). Each node gets its own `CLAUDE.md` that constrains what the agent is allowed to do.

```
graphs/examples/todo-app-dag.edn
  scaffold → implement-core → implement-handlers → implement-ui → implement-tests → review (checkpoint)
```

Use this when you want full control over decomposition and sequencing.

### Wiggum loop

You state an objective and a success check (`npm test`, `clj -M:test`, etc.). The loop runs the executor agent (`claude` or `pi`), captures evidence (files written/edited, verification exit code, token cost), and passes that to a supervisor LLM that emits a tighter control packet for the next iteration. Repeats until the success check passes or max iterations is reached.

```
graphs/examples/todo-app-wiggum.edn          (Clojure + htmx)
graphs/examples/todo-app-react-wiggum.edn    (React + TypeScript)
graphs/examples/pi-kanban-fullstack.edn      (pi executor)
```

Use this when you want the system to handle decomposition and course-correction autonomously.

## Running

### Prerequisites

- Clojure CLI
- Claude Code CLI (`claude --version`) with a **Max plan** or `ANTHROPIC_API_KEY` set
  - Pro plan covers interactive sessions but not `--print` subprocess calls
- (optional) **pi CLI** (`pi --version`) — `npm install -g @earendil-works/pi-coding-agent`

### CLI (simplest)

```bash
export ANTHROPIC_API_KEY=sk-ant-...

# Wiggum loop — state the goal, let the system figure out the steps
clj -M:run graphs/examples/todo-app-wiggum.edn --project-dir /tmp/my-todo-app

# DAG — explicit node graph with a human review checkpoint at the end
clj -M:run graphs/examples/todo-app-dag.edn --project-dir /tmp/my-todo-app

# Step-once mode — pause after each iteration to review
clj -M:run graphs/examples/todo-app-wiggum.edn --project-dir /tmp/my-todo-app --step
```

The terminal streams events as nodes/iterations complete. Checkpoint nodes prompt interactively (`approve / revise / abort`). The UI is live at `http://localhost:7777/runs/<project-name>` while the run is active.

### REPL

```bash
clj -M:nrepl   # nREPL on port 7888
```

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

## The judge and end-of-iteration review

Wiggum runs aren't fully autonomous — at certain points, the loop pauses and waits for you. Three things can trigger a pause:

- **Judge pause** — at the end of every phase (an iteration whose `expected_files` are all present), an LLM judge inspects the git diff against the previously-accepted state, scores it 0–10, and emits violations tagged `high` / `medium` / `low`. If any violation meets the configured `:review-threshold` (default `:high`), the run pauses.
- **Milestone pause** — every `:checkpoint-every` iterations, regardless of the judge.
- **Step pause** — after every iteration when `:step-once?` is true (or you toggle the **Auto / Step ON** button in the UI).

Open `http://localhost:7777/runs/<id>` to see the review panel. It shows the iteration's exit code, runtime, files added/edited, verification result, the judge's score and recommendation, the grouped violation list, a feedback textarea, and the action buttons below.

### What you can do

| Button | When it appears | What it does |
|--------|-----------------|--------------|
| **Continue** | always | Accept the iteration and move on. Anything you typed in the textarea is folded into the next control packet as `## Human reviewer feedback`. |
| **Retry** | always | Re-run the same iteration with the same control packet (useful after a transient failure). |
| **Restore** | only when the judge produced a review | `git restore` the work-dir to the state before this iteration and re-enter the phase. Use when the iteration did damage. |
| **Ignore judge** | only when the judge produced a review | Drop the judge's review from the iteration's evidence and continue. The supervisor won't see the judge's complaints. Use when the judge is stuck or wrongly flagging. |
| **Abort** | always | Stop the run. |

The textarea is stable across SSE events — you can type while iterations stream behind the panel without losing focus or content.

### Feedback semantics

How the textarea content is interpreted depends on what you write:

- **Empty + Continue** — the supervisor sees the full judge review and follows the default rules: HIGH violations *must* be addressed, medium are folded into constraints/anti-goals, low are noted only on patterns.
- **Targeted feedback** (`"fix the 1st and 2nd"`, `"address the medium ones"`, `"ignore the auth issue"`) — the supervisor treats only your instruction as the worklist; the judge violation list becomes reference material, not a to-do.
- **Broad feedback** (`"focus on tests"`, `"keep going on X"`) — the supervisor follows your direction and falls back to default judge handling for anything you didn't reference.
- **Feedback at a verified iteration** — normally a verified iteration finishes the run; if you supply feedback at that pause, Wiggum runs one more iteration to apply it instead of terminating.
- **Feedback when `:max-iterations` would be hit** — the cap is automatically bumped by 1 so your feedback isn't silently dropped.

### Config knobs

```clojure
{:review-threshold :high       ; :none | :low | :medium | :high
 :judge-mode       :code       ; :code | :spec | :research
 :step-once?       false       ; pause after every iteration
 :checkpoint-every nil}        ; pause every N iterations regardless of judge
```

- `:review-threshold :none` disables the judge entirely.
- `:judge-mode` selects the rubric the judge runs against:
  - `:code` (default) — strict code review against expected_files, constraints, anti-goals.
  - `:spec` — review of an automatically-generated Wiggum implementation spec EDN.
  - `:research` — citation discipline, source diversity, faithfulness to source.

Pre-existing expected files (carried over from prior iterations) are not violations. The judge sees a clear breakdown of what was carried over, what was produced this iteration, and what is genuinely missing.

## Writing a graph

### DAG node

```clojure
{:id          :my-node
 :type        :task
 :depends-on  [:prior-node]
 :project-dir [:graph/params :project-dir]   ; resolved from context
 :claude-md   "# Task: my-node\n## Your job\n..."
 :prompt      "Do X at {{project-dir}}."
 :inputs      {:project-dir [:graph/params :project-dir]}
 :done-check  "clj -M:test"
 :output-key  :my-node/output}
```

### Wiggum config (full key reference)

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
 ;; Shell commands the supervisor runs at the end of each phase to confirm it
 ;; is done before advancing. Format: --- Phase N (name) --- / Command: / Required: *
 :acceptance-criteria nil

 ;; Model selection
 :model-config            nil   ; LLM for both executor and supervisor
 :executor-model-config   nil   ; overrides executor only
 :supervisor-model-config nil   ; overrides supervisor only
 :fallback-model          nil   ; model-id to retry on Anthropic rate-limit (claude agent only)
 :fallback-delay-ms       30000 ; ms to wait before fallback retry

 ;; Review / judge
 :judge-mode       :code   ; :code | :spec | :research
 :review-threshold :high   ; :none | :low | :medium | :high

 ;; Run behaviour
 :step-once?          false  ; pause after every iteration
 :checkpoint-every    nil    ; pause every N iterations regardless of judge
 :generate-claude-md? true   ; write a CLAUDE.md to the project root after success
 :polish-pass?        false  ; one extra pass after verification to add WHY comments

 ;; Auto-steer (stuck-loop recovery)
 :auto-steer?      false  ; when true, diagnose why the executor is stuck and
                          ; inject a pivot suggestion into the supervisor after
                          ; :stuck-threshold consecutive verify failures
 :stuck-threshold  3}     ; how many consecutive failures before triggering a pivot
```

**Auto-steer** calls an LLM to analyse recent failed evidence (verification output, files tried) and generates a context-specific pivot suggestion that feeds into the next supervisor call as human-style feedback. Works with both `claude` and `pi` executors.

## Agents and providers

Morpheus picks an LLM on two axes, both set in `:model-config` (or the per-side overrides):

- **`:agent`** — which CLI binary does the work: `:claude` (default, the `claude` CLI) or `:pi` (the `pi` CLI).
- **`:provider`** — within that agent, which backend endpoint to hit.

### Claude agent providers

The `:claude` agent shells out to `claude --print`. Non-Anthropic providers point it at an Anthropic-compatible endpoint.

| Provider    | Backend                                   | Required env        |
|-------------|-------------------------------------------|---------------------|
| (default)   | `claude --print` → api.anthropic.com      | `ANTHROPIC_API_KEY` |
| `:ollama`   | local Ollama server, auto-pulls model     | none                |
| `:kimi`     | `claude --print` → api.moonshot.ai        | `MOONSHOT_API_KEY`  |

`:minimax` is supported for the **supervisor** (LLM calls) but not for the claude executor.

### Pi agent providers

The `:pi` agent routes through the `pi` CLI, which has its own provider list.

| `:provider` | Backend                    | Required env        |
|-------------|----------------------------|---------------------|
| (default)   | pi's default routing       | per-provider        |
| `:kimi`     | Moonshot AI (kimi-k2.x)    | `MOONSHOT_API_KEY`  |
| `:minimax`  | MiniMax                    | `MINIMAX_API_KEY`   |
| `:anthropic`| Anthropic via pi           | `ANTHROPIC_API_KEY` |
| `:openai`   | OpenAI via pi              | `OPENAI_API_KEY`    |
| `:google`   | Google via pi              | `GOOGLE_API_KEY`    |
| `:ollama`   | local Ollama via pi        | none                |

Any provider keyword not in the list above is passed through verbatim to pi's `--provider` flag.

### Ollama (local)

```clojure
{:objective     "..."
 :success-check "clj -M:test"
 :executor-model-config   {:provider :ollama :model-id "qwen2.5-coder:32b"}
 :supervisor-model-config {:model-id "claude-sonnet-4-6"}}
```

Requires the `ollama` CLI on `PATH`. The model is pulled the first time it runs.

### Kimi (Moonshot)

```clojure
{:objective     "..."
 :success-check "clj -M:test"
 :executor-model-config   {:provider :kimi :model-id "kimi-k2.5"}
 :supervisor-model-config {:model-id "claude-sonnet-4-6"}}
```

```bash
export MOONSHOT_API_KEY=sk-...
```

### Pi agent

Set `:agent :pi` to run the `pi` CLI instead of `claude`. pi does its own
provider routing — `:provider` uses Morpheus's keyword (`:kimi`, `:minimax`, etc.) and `:model-id` is the model name the provider exposes:

```clojure
{:objective     "..."
 :success-check "npm test"
 :executor-model-config   {:agent :pi :provider :kimi    :model-id "kimi-k2.6"}
 :supervisor-model-config {:agent :pi :provider :minimax :model-id "MiniMax-M2.7"}}
```

```bash
npm install -g @earendil-works/pi-coding-agent
export MOONSHOT_API_KEY=sk-...     # for :kimi
export MINIMAX_API_KEY=sk-...      # for :minimax
```

`:agent :pi` works everywhere a model-config does — executor, supervisor, and `:executor :llm` nodes.

pi streams its thinking and tool calls into the live UI just like claude. The subprocess runner handles pi's background `context-mode` daemon: it stops at pi's `agent_end` event and reaps the daemon, so a pi iteration terminates cleanly.

Rate-limit fallback (`:fallback-model`) applies only to the `:claude` agent. A rate-limited pi run surfaces as an exhaustion pause for you to resume.

### Mixed setup

`:executor-model-config` and `:supervisor-model-config` can each use a different agent and provider:

```clojure
;; pi executor, Anthropic supervisor
{:executor-model-config   {:agent :pi :provider :kimi :model-id "kimi-k2.6"}
 :supervisor-model-config {:model-id "claude-sonnet-4-6"}}

;; Kimi executor, MiniMax supervisor (all via pi)
{:executor-model-config   {:agent :pi :provider :kimi    :model-id "kimi-k2.6"}
 :supervisor-model-config {:agent :pi :provider :minimax :model-id "MiniMax-M2.7"}}
```

If `:fallback-model` is set, it always runs against vanilla Anthropic — the executor's `:provider` is reset on rate-limit retry so the fallback id isn't sent to a non-Anthropic endpoint.

## Node types for DAG nodes

| Type           | Executor              | Use for                                    |
|----------------|-----------------------|--------------------------------------------|
| `:task`        | claude or pi CLI      | Any unit of work needing file tools        |
| `:planning`    | claude or pi CLI      | PRD → milestone sections                  |
| `:parallel`    | claude or pi CLI      | N concurrent agent sessions                |
| `:checkpoint`  | none                  | Human review gate                          |
| `:graph-expand`| pure fn               | Splice new nodes into the live graph       |
| `:subgraph`    | recursive engine      | Run a nested graph                         |
| `:shell`       | sh subprocess         | Run tests, build commands, etc.            |
| `:http`        | http-kit              | Webhooks, external APIs                    |

### Extending

**New node type:** add keyword to `graph/schema.clj`, add `defmethod execute-node!` in `executor/dispatch.clj`, add hiccup rendering in `ui/components.clj`.

**New CLAUDE.md template:** add a function to `graphs/expanders.clj` taking `[milestone-id brief project-dir]` and returning a string.

**New graph:** create an EDN file in `graphs/`. No code registration needed.
