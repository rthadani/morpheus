# Morpheus — agent orchestrator

A data-driven agent orchestration system where Claude Code *is* the executor.
Graphs are EDN data. Each task node runs a Claude Code subprocess with a
purpose-built CLAUDE.md that scopes it to its specific job.

## Stack

| Layer       | Library                        |
|-------------|-------------------------------|
| HTTP server | http-kit                      |
| Routing     | reitit                        |
| HTML        | hiccup2                       |
| Async       | core.async                    |
| Config      | aero + integrant              |
| LLM         | Claude Code CLI subprocess    |
| LLM fallback| Anthropic API (direct HTTP)   |

## Architecture

```
graphs/             EDN graph definitions (data, no code)
src/morpheus/
  system.clj        Integrant system + REPL helpers (go!, start!, stop!)
  graph/
    schema.clj      Node constructors and validation — pure data
    topo.clj        Topological sort, runnable-nodes — pure functions
    context.clj     Context store, input resolution, prompt rendering — pure
  executor/
    claude_code.clj Claude Code subprocess runner + CLAUDE.md generation
    llm.clj         Raw Anthropic API wrapper (fallback for :executor :llm)
    dispatch.clj    Multimethod: execute-node! dispatches on :type
    engine.clj      Core executor loop — core.async go-loop
    store.clj       In-memory run store (atom)
  ui/
    components.clj  Hiccup components — pure functions
    router.clj      Reitit routes + SSE handler
  graphs/
    expanders.clj   Graph expansion functions (milestones->nodes)
```

## How Claude Code is used as executor

Each `:task` or `:planning` node:
1. Creates a temp working directory
2. Writes a `CLAUDE.md` scoped to that node's job
3. Optionally copies in the real project (`project-dir`)
4. Runs `claude --print "<prompt>"` as a subprocess
5. Captures stdout as the node's output
6. Stores the list of files written back into context

The `:claude-md` field on a node can be:
- A string (used as-is, with `{{slot}}` interpolation)
- A keyword pointing to a context key that holds the md content
- `nil` — a minimal default is generated from `:prompt` and `:done-check`

Nodes with `:executor :llm` skip Claude Code and call the API directly.
Use this for simple extraction/classification tasks that don't need file tools.

## Node types

| Type           | Executor         | Use for                                      |
|----------------|------------------|----------------------------------------------|
| `:task`        | Claude Code CLI  | Any unit of work needing file tools          |
| `:planning`    | Claude Code CLI  | PRD → milestone sections (plan mode)         |
| `:parallel`    | Claude Code CLI  | N concurrent Claude Code sessions            |
| `:checkpoint`  | none             | Human review gate                            |
| `:graph-expand`| pure fn          | Splice new nodes into live graph             |
| `:subgraph`    | recursive engine | Run a nested graph                           |
| `:shell`       | sh subprocess    | Run tests, build commands, etc.              |
| `:http`        | http-kit         | Webhooks, external APIs                      |

## Key node config fields

```clojure
{:id          :my-node
 :type        :task
 :depends-on  [:prior-node]

 ;; Claude Code config
 :claude-md   "# Task: ...\n\n## Your job\n..."   ; scopes the CC session
 :project-dir "/path/to/your/project"              ; CC reads this codebase
 :done-check  "clj -M:test"                        ; CC runs this to verify
 :executor    :claude-code                          ; or :llm for raw API

 ;; Prompt + inputs
 :prompt      "Do X.\n\nContext:\n{{prior-output}}"
 :inputs      {:prior-output [:some-node/output]}

 ;; Hooks
 :hooks       {:pre  my.ns/pre-hook
               :post my.ns/post-hook}}
```

## Context store

Plain Clojure map. Outputs stored under `:output-key` (qualified keyword).
Inputs resolved as `get-in` paths: `[:planning/sections :arch-design]`.
Prompt slots rendered with `{{slot-name}}`.

## Checkpoint / human-in-the-loop

1. Executor hits `:checkpoint` node
2. `execute-node!` puts `{:type :checkpoint ...}` on `event-ch`
3. SSE pushes checkpoint panel fragment to browser
4. Human reviews Claude Code output (files, diffs) and optionally adds feedback
5. POST to `/runs/:id/checkpoint/:node-id` with action + feedback
6. Handler calls `engine/resume!` → puts action on `resume-ch`
7. Executor resumes / re-runs with feedback injected into CLAUDE.md / aborts

## Graph self-rewriting

1. `:planning` node produces `{:sections {milestone-id "brief..."} ...}`
2. `:graph-expand` node calls `milestones->nodes` (pure fn)
3. Each milestone becomes: `subgraph node` → `checkpoint node`
4. Each task inside a subgraph gets a purpose-built CLAUDE.md
5. Executor `swap!`s new nodes into `graph-atom` and continues topo walk

## REPL

```clojure
(require '[morpheus.system :as sys])
(sys/go!)   ; server on port 7777

;; Run a graph
(require '[clojure.edn :as edn]
         '[morpheus.executor.engine :as engine])

(def graph (edn/read-string (slurp "graphs/examples/wifi-qoe-service.edn")))
(def run   (engine/execute! 1 graph
             {:raw-prd      "...your PRD..."
              :graph/params {:project-dir "/path/to/your/project"}}))

;; Inspect
@(:state run)
@(:context run)

;; Manually approve checkpoint
(engine/resume! run {:action :approve :node-id :review-plan})
```

## Environment

```bash
export ANTHROPIC_API_KEY=sk-ant-...
clj -M:nrepl        # nREPL on port 7888
clj -M:test         # run tests
claude --version    # verify Claude Code CLI is installed
```

## Extending

### Add a new node type
1. Add keyword to `node-types` in `graph/schema.clj`
2. Add `defmethod execute-node!` in `executor/dispatch.clj`
3. Add hiccup rendering in `ui/components.clj` if needed

### Add a new CLAUDE.md template
Add a function to `graphs/expanders.clj` following the pattern of
`arch-claude-md`, `implement-claude-md` etc.
The function should take `[milestone-id brief project-dir]` and return a string.

### Add a new graph
Create an EDN file in `graphs/`. No code registration needed.
Set `:project-dir` in `:graph/params` to point Claude Code at your codebase.

## Conventions

- All node handlers receive `[node inputs context graph-atom event-ch]`
- `execute-node!` returns the value stored under `:output-key`
- SSE events use `hx-swap-oob` for targeted DOM updates
- Hiccup components are pure functions — no atoms, no I/O
- `graph/` namespaces are pure — no I/O, no side effects
