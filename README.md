# SwarmForge

**A single-binary agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

SwarmForge coordinates several AI coding-agent CLIs (`claude`, `codex`, `copilot`, `grok`) working in parallel on the same project: each configured role gets its own git worktree and its own pane in a built-in terminal UI, and roles hand off work to each other through a durable, file-based message queue.

This is a from-scratch Go rewrite of the original Babashka/shell implementation. There is no more tmux, no OS-specific terminal-emulator adapters, and no `./swarm` curl+tar bootstrap — one `swarmforge` binary does everything, and it draws its own multi-pane view directly in your terminal.

## Prerequisites

- `git`
- Go 1.24+ (only to build; the built binary has no further runtime dependency beyond the agent CLIs below)
- At least one configured agent backend: `claude`, `codex`, `copilot`, or `grok`

## Install

```sh
go install github.com/TorratDev/swarm-forge/cmd/swarmforge@latest
```

or build from a checkout:

```sh
go build -o swarmforge ./cmd/swarmforge
```

## Getting Started

Scaffold a new project from one of the built-in packs (see [Packs](#packs) below):

```sh
swarmforge init --pack two-pack path/to/project
cd path/to/project
swarmforge up
```

`init` writes `swarmforge.yaml` plus `swarmforge/roles/*.prompt` and `swarmforge/constitution/` into the target directory — plain files you can edit afterward, generated once and never overwritten. `up` then validates that config, creates a git worktree per role, pre-accepts Claude Code's workspace-trust dialog for `claude` roles, launches every role's agent CLI under its own pseudo-terminal, and takes over your terminal with the swarm view.

To stop a swarm from another terminal:

```sh
swarmforge down path/to/project   # defaults to the current directory
```

### TUI controls

There's no tmux prefix key anymore, but the idea carries over: **Ctrl-A** is the leader key. Press it, then:

- a digit (`1`-`9`) switches the focused pane to that role
- `q` quits and tears down the whole swarm

Every other keystroke — including Ctrl-C — passes straight through to the focused pane's agent process, exactly as if you'd typed it directly into that CLI.

Closing the pane belonging to the **first role listed** in `swarmforge.yaml` (the "cleanup role") tears down the entire swarm, matching the rest of the roles' teardown behavior.

## Packs

A pack is a declarative role topology, embedded in the `swarmforge` binary and materialized by `init`. Three ship today:

| Pack | Roles | Flow |
|---|---|---|
| `two-pack` | `coder`, `cleaner` | `coder` → `cleaner` → `coder` — a quick implement/refine loop, no specification or QA overhead |
| `four-pack` | `specifier`, `coder`, `refactorer`, `architect` | `specifier` → `coder` → `refactorer` → `architect` → `specifier` — Gherkin specification plus one architecture/hardening pass |
| `six-pack` | `specifier`, `coder`, `cleaner`, `architect`, `hardender`, `QA` | `specifier` → `coder` → `cleaner` → `architect` → `hardender` → `QA` → completion — every quality gate as its own role |

List and lint the embedded packs:

```sh
swarmforge pack list
swarmforge pack lint          # lints every pack
swarmforge pack lint six-pack # lints just one
```

## The `swarmforge.yaml` File

`init` generates this from a pack; `up` reads whatever is on disk, so hand edits stick. It's the data-driven replacement for the old `window <role> <agent> <worktree> [mode] [args]` config-file DSL:

```yaml
name: two-pack
roles:
  - name: coder
    agent: claude
    worktree: master
    receive_mode: task
    extra_args: ["--model", "haiku"]
  - name: cleaner
    agent: claude
    worktree: cleaner
    receive_mode: batch
    extra_args: ["--model", "sonnet"]
```

- `name` maps to `swarmforge/roles/<name>.prompt`; must be unique and must not contain `_`.
- `agent` is one of `claude`, `codex`, `copilot`, `grok`.
- `worktree` is `master` (or `none`) to run in the main working directory, or any other unique name to get `.worktrees/<name>` on branch `swarmforge-<name>`.
- `receive_mode` is `task` (default) or `batch`. `batch` roles consume every currently queued equal-priority handoff as one batch instead of one task at a time.
- `extra_args` are passed straight through to the agent CLI's argv.
- The **first role in the list** is the cleanup role (see [TUI controls](#tui-controls)).

### Permission mode for `claude` and `grok` roles

SwarmForge auto-injects a permission-mode flag so an unattended agent doesn't stall waiting for approval. By default it injects `--permission-mode acceptEdits` (auto-approves file edits, still stops on Bash/tool calls). To let a role auto-approve everything, add `--yolo`, `--always-approve`, or `--permission-mode bypassPermissions` to that role's `extra_args`:

```yaml
  - name: coder
    agent: claude
    worktree: coder
    extra_args: ["--yolo"]
```

`bypassPermissions` is opt-in per role — never the default.

## Handoff Protocol

Agents don't message each other directly. Each role's worktree gets a `.swarmforge/handoffs/` directory (`outbox`, `sent`, `failed`, `inbox/{new,in_process,completed}`), and while a swarm is running, a delivery goroutine inside `swarmforge up` polls every role's outbox, copies validated handoffs into each recipient's inbox, and wakes the recipient by writing directly into its pane.

Agents interact with this queue through three commands (also installed on `PATH` under their original script names — `swarm_handoff.sh`, `ready_for_next.sh`, `done_with_current.sh` — so existing role prompts work unmodified):

- `swarm_handoff.sh <draft-file>` validates a draft and queues it into the sender's outbox.
- `ready_for_next.sh` accepts the next task or batch, per the role's configured receive mode.
- `done_with_current.sh` completes the current task or batch, then immediately reports the next one.

A draft is headers only; the command generates the delivered payload. Two message types:

```text
type: git_handoff
to: <role>[,<role>...]
priority: NN
task: <short-stable-task-name>
commit: <10-character-commit-abbrev>
```

```text
type: note
to: <role>[,<role>...]
priority: NN
message: <one line, max 80 chars>
```

`commit` must resolve to exactly one real commit object; `swarm_handoff.sh` canonicalizes it before queuing. `priority` is two digits, lower delivered first; handoff filenames (`<priority>_<timestamp>_<sequence>_from_<sender>_to_<recipients>.handoff`) are constructed to sort lexicographically in exactly that delivery order.

Recipients run `ready_for_next.sh` when notified or on restart. `NO_TASK` means stop waiting; `TASK: <path>` means treat the printed `PAYLOAD` as the task; `BATCH: <path>` means process each printed `BATCH_ITEM` in order. `done_with_current.sh` after finishing immediately reports the next task/batch the same way. Agents should never hand-edit, merge, stage, or commit anything under `.swarmforge/`.

## Constitution Structure

```text
swarmforge/
  roles/
    <role>.prompt
  constitution.prompt
  constitution/
    articles/
      engineering.prompt   # shared base article
      handoffs.prompt      # shared base article
      workflow.prompt      # shared base article
      project.prompt       # pack-specific overlay
      ...
```

`constitution.prompt` tells every agent to read every file under `constitution/articles/`. `engineering.prompt`, `handoffs.prompt`, and `workflow.prompt` are shared base articles every pack includes by default (`swarmforge pack lint` fails a pack that drops `handoffs` without an explicit replacement). Packs add their own overlay files — `project.prompt` describes the pack's role topology; `six-pack` additionally ships `local-engineering.prompt` and `local-workflow.prompt` as small additive specializations that sit alongside the base articles rather than replacing them.

## Development

```sh
go build ./...
go test ./...
go test -race ./...
```

Package layout:

```text
cmd/swarmforge/       entry point; argv0 dispatch for the legacy script names
internal/
  cli/                command handlers (init, up, down, handoff, ready, done, pack)
  config/              swarmforge.yaml schema + validation
  state/               .swarmforge/state.json + project-root discovery
  handoff/             header parse/serialize, filenames, draft validation, queue state machine
  daemon/               outbox -> inbox delivery poll loop
  gitutil/               git plumbing: worktrees, commit canonicalization
  trust/                 ~/.claude.json trust-dialog patcher
  launch/                 per-backend argv builders
  orchestrator/           startup sequencing, live swarm (launch/daemon/teardown)
  ptyagent/               PTY-backed subprocess spawn/resize/teardown
  termemu/                vt10x-backed virtual screen per agent
  tui/                    the multi-pane bubbletea app
  pack/                   pack schema, embedded pack definitions, generator
```
