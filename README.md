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

Less poetically: **Morpheus is an agent orchestration loop.** You state a goal and a success check; Morpheus runs `claude` (or `pi`) as a subprocess, captures the evidence, passes it to a supervisor LLM that writes a tighter brief for the next iteration, and repeats until the check passes.

## Prerequisites

- Clojure CLI
- Claude Code CLI with a Max plan or `ANTHROPIC_API_KEY` set
- (optional) pi CLI — `npm install -g @earendil-works/pi-coding-agent`

## Running a spec

```bash
export ANTHROPIC_API_KEY=sk-ant-...
clj -M:run graphs/examples/todo-app-wiggum.edn --project-dir /tmp/my-app
```

The UI is live at `http://localhost:7777/runs/<id>` while the run is active. When the judge flags a violation (or you enable `:step-once?`) the run pauses for your review — approve, retry, restore, or abort from the browser.

## Generating a spec

Instead of writing a Wiggum EDN file by hand, run the spec generator. Pass your description on the command line:

```bash
clj -M:run graphs/spec-generator.edn \
  --project-dir ./spec-out \
  --description "A Kafka consumer lag monitor dashboard in React + Node.js"
```

Append an optional provider suffix to the description to select the model:

| Suffix     | Routes through              | Required env       |
|------------|-----------------------------|--------------------|
| (none)     | claude → Anthropic          | `ANTHROPIC_API_KEY`|
| `:kimi`    | claude → api.moonshot.ai    | `MOONSHOT_API_KEY` |
| `:ollama`  | claude → local Ollama       | none               |
| `:pi`      | pi CLI                      | per-provider       |

Then review and run the output:

```bash
# output is in spec-out/output/<slug>.edn
clj -M:run spec-out/output/<slug>.edn --project-dir /path/to/your/project
```

## Full app from a description

`graphs/app-generator.edn` chains the two steps above into a single run:

1. **Checkpoint** — asks you for a description of the app you want built
2. **Generate spec** — runs the spec generator as a sub-run
3. **Build app** — runs the generated spec as a second sub-run

```bash
clj -M:run graphs/app-generator.edn --project-dir /path/to/new/project
```

The UI shows each sub-run as a link you can open in a new tab. The spec is shown for your review at the checkpoint before the build starts.

## Step mode

```bash
clj -M:run graphs/examples/todo-app-wiggum.edn --project-dir /tmp/my-app --step
```

Pauses after every iteration. Toggle back to auto with the **Step ON** button in the UI.

## Full reference

See [docs/reference.md](docs/reference.md) for:
- Full Wiggum config key reference
- All agent/provider options (Kimi, Ollama, pi, MiniMax, mixed setups)
- Judge and review panel details
- DAG executor and node types
- REPL usage
