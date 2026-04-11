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

## Architecture

```
graphs/                   EDN run configs — pure data, no code
src/morpheus/
  system.clj              Integrant system + REPL helpers (go!, start!, stop!)
  main.clj                CLI entry point (clj -M:run)
  graph/
    schema.clj            Node constructors and validation
    topo.clj              Topological sort, runnable-nodes
    context.clj           Context store, input resolution, prompt rendering
  executor/
    claude_code.clj       Spawns claude --print as a subprocess, generates CLAUDE.md per node
    llm.clj               Thin wrapper around claude --print for supervisor calls
    wiggum.clj            Iteration loop: run → evidence → supervisor → repeat
    supervisor.clj        Reviews evidence, emits next control packet (calls llm.clj)
    evidence.clj          Captures file diffs, slop signals, verification results
    dispatch.clj          Multimethod: execute-node! dispatches on :type
    engine.clj            DAG executor loop (core.async go-loop)
    store.clj             In-memory run store
  ui/
    components.clj        Hiccup components — pure functions
    router.clj            Reitit routes + SSE handler
  graphs/
    expanders.clj         Graph expansion functions (planning → milestone nodes)
```

## Running

### Prerequisites

- Clojure CLI
- Claude Code CLI (`claude --version`) with a **Max plan** or `ANTHROPIC_API_KEY` set
  - Pro plan covers interactive sessions but not `--print` subprocess calls

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
