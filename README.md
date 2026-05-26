# Morpheus

An agent orchestration system where **Claude Code is the executor**. You describe work as data (EDN), Morpheus runs Claude Code subprocesses to do it, and a supervisor steers between iterations based on evidence.

## How it works

Morpheus has two execution models:

### DAG executor

You define a graph of task nodes with explicit dependencies. Morpheus walks the graph topologically, running a scoped Claude Code session per node. Each node gets its own `CLAUDE.md` that constrains what Claude Code is allowed to do.

```
graphs/examples/todo-app-dag.edn
  scaffold → implement-core → implement-handlers → implement-ui → implement-tests → review (checkpoint)
```

Use this when you want full control over decomposition and sequencing.

### Wiggum loop

You state an objective and a success check (`npm test -- --run`, `clj -M:test`, etc.). The loop runs Claude Code, captures evidence (files written/edited, verification exit code), and passes that to a supervisor LLM that emits a tighter control packet for the next iteration. Repeats until the success check passes or max iterations is reached.

```
graphs/examples/todo-app-wiggum.edn          (Clojure + htmx)
graphs/examples/todo-app-react-wiggum.edn    (React + TypeScript)
```

Use this when you want the system to handle decomposition and course-correction autonomously.

## Running

### Prerequisites

- Clojure CLI
- Claude Code CLI (`claude --version`) with a **Max plan** or `ANTHROPIC_API_KEY` set
  - Pro plan covers interactive sessions but not `--print` subprocess calls
- (optional) **pi CLI** (`pi --version`) to run on the `:pi` agent instead of `claude` — `npm install -g @earendil-works/pi-coding-agent`

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

The terminal streams events as nodes/iterations complete. Checkpoint nodes prompt interactively (`approve / revise / abort`). The UI is live at `http://localhost:7777/runs/1` while the run is active.

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
| **Continue →** | always | Accept the iteration and move on. Anything you typed in the textarea is folded into the next control packet as `## Human reviewer feedback`. |
| **Retry ↺** | always | Re-run the same iteration with the same control packet (useful after a transient failure). |
| **Restore ⎌** | only when the judge produced a review | `git restore` the work-dir to the state before this iteration and re-enter the phase. Use when the iteration did damage. |
| **Ignore judge ⤳** | only when the judge produced a review | Drop the judge's review from the iteration's evidence and continue. The supervisor won't see the judge's complaints. Use when the judge is stuck or wrongly flagging. |
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

Pre-existing expected files (carried over from prior iterations) are not violations. The judge sees a clear breakdown of what was carried over, what was produced this iteration, and what is genuinely missing — so an iteration that satisfies the goal entirely from prior work won't be flagged as "agent did nothing."

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

### Wiggum config

```clojure
{:objective     "Build a working X that does Y and Z."
 :project-dir   nil            ; set at runtime
 :success-check "npm test -- --run"
 :constraints   ["Write tests alongside implementation"]
 :anti-goals    ["Do not add dependencies that aren't immediately used"]
 :max-iterations 15
 :step-once?    false}
```

## Agents and providers

Morpheus picks an LLM on two axes, both set in `:model-config`:

- **`:agent`** — which CLI binary does the work: `:claude` (default, the
  `claude` CLI) or `:pi` (the `pi` CLI). This is the same map everywhere a
  model is chosen — the supervisor, `:task` nodes, and `:executor :llm` nodes.
- **`:provider`** — within that agent, which backend endpoint to hit.

The `:claude` agent shells out to `claude --print`; non-Anthropic providers just
point `claude` at an Anthropic-compatible endpoint.

| Provider   | How it runs                                            | Required env       |
|------------|--------------------------------------------------------|--------------------|
| `:claude`  | `claude --print` against api.anthropic.com (default)   | `ANTHROPIC_API_KEY`|
| `:ollama`  | `ollama launch claude` — auto-starts server, auto-pulls model | none (local)|
| `:kimi`    | `claude --print` against api.moonshot.ai/anthropic     | `MOONSHOT_API_KEY` |

`:model-config` sets both executor and supervisor; use `:executor-model-config`
or `:supervisor-model-config` to override one side.

### Ollama (local)

```clojure
{:objective     "..."
 :success-check "clj -M:test"
 :model-config
 {:provider :ollama
  :model-id "qwen2.5-coder:32b"}}   ; required — pulled on first use
```

Requires the `ollama` CLI on `PATH`. The model is pulled the first time it runs.

### Kimi (Moonshot)

```clojure
{:objective     "..."
 :success-check "clj -M:test"
 :model-config
 {:provider :kimi
  :model-id "kimi-k2.5"                              ; default
  :base-url "https://api.moonshot.ai/anthropic"}}    ; default
```

```bash
export MOONSHOT_API_KEY=sk-...
```

### Pi agent

Set `:agent :pi` to run the `pi` CLI instead of `claude`. pi does its own
provider routing, so `:model-id` uses pi's `provider/model` naming:

```clojure
{:objective     "..."
 :success-check "npm test"
 :model-config  {:agent :pi :model-id "moonshotai/kimi-k2.6"}}
```

```bash
npm install -g @earendil-works/pi-coding-agent   # pi on PATH
export MOONSHOT_API_KEY=sk-...                    # whatever the model needs
```

`:agent :pi` works everywhere a model-config does:

- **Wiggum** — executor and supervisor each honour `:agent`. Put it on
  `:model-config` for both, or split via `:executor-model-config` /
  `:supervisor-model-config` (e.g. pi executor, claude supervisor).
- **`:task` nodes** — set `:executor :pi` on the node, or `:agent :pi` in its
  model-config. `:executor :claude` (or the default) keeps the `claude` CLI.
- **`:executor :llm` nodes** — the lightweight no-filesystem path dispatches on
  `:agent` as well.

pi streams its thinking and tool calls into the live UI just like claude. The
subprocess runner also handles pi's background `context-mode` daemon: it stops
at pi's `agent_end` event and reaps the daemon, so a pi iteration terminates
cleanly instead of hanging on a stdout pipe the daemon holds open.

`:fallback-model` rate-limit retry applies only to the `:claude` agent; a
rate-limited pi run surfaces as an exhaustion pause for you to resume.

Example: `graphs/examples/pi-kanban-fullstack.edn`.

### Mixed setup

Both `:executor-model-config` and `:supervisor-model-config` accept the same
`:provider` dispatch — you can run the executor on Kimi and reason with Sonnet
on the supervisor side, or any other split:

```clojure
{:executor-model-config   {:provider :kimi   :model-id "kimi-k2.5"}
 :supervisor-model-config {:provider :claude :model-id "claude-sonnet-4-6"}}
```

If `:fallback-model` is set, it always runs against vanilla Anthropic — the
executor's `:provider` is reset on rate-limit retry so a fallback id like
`claude-haiku-4-5-20251001` doesn't get sent to e.g. Moonshot.

## Node types

| Type           | Executor         | Use for                                    |
|----------------|------------------|--------------------------------------------|
| `:task`        | Claude Code CLI  | Any unit of work needing file tools        |
| `:planning`    | Claude Code CLI  | PRD → milestone sections                  |
| `:parallel`    | Claude Code CLI  | N concurrent Claude Code sessions          |
| `:checkpoint`  | none             | Human review gate                          |
| `:graph-expand`| pure fn          | Splice new nodes into the live graph       |
| `:subgraph`    | recursive engine | Run a nested graph                         |
| `:shell`       | sh subprocess    | Run tests, build commands, etc.            |
| `:http`        | http-kit         | Webhooks, external APIs                    |

## Extending

**New node type:** add keyword to `graph/schema.clj`, add `defmethod execute-node!` in `executor/dispatch.clj`, add hiccup rendering in `ui/components.clj`.

**New CLAUDE.md template:** add a function to `graphs/expanders.clj` taking `[milestone-id brief project-dir]` and returning a string.

**New graph:** create an EDN file in `graphs/`. No code registration needed.
