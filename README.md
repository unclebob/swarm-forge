<p align="center" style="color: red; font-weight: bold; font-size: 2em; font-style: italic; text-decoration: underline;">
Do not spend any money on a bankrbot SWARM token.
</p>

# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

This `main` branch is documentary: it explains the system and carries the shared operational scripts and default constitution articles. The runnable workflow branches carry the project-facing configurations, role prompts, and local constitution articles that define specific workflows.

SwarmForge is an agent coordination system that facilitates communication between agents working in different git worktrees.

It provides a shared structure for role-specific prompts, worktree assignment, tmux sessions, and message passing so multiple agents can collaborate on the same project without stepping on each other.

## Branches

The runnable SwarmForge configurations live on dedicated branches. Each branch contains the `swarmforge/swarmforge.conf`, local constitution articles, and role prompts for one workflow. At startup, its `./swarm` wrapper copies the shared operational scripts and shared constitution articles from `main` when they are not already present, then launches that branch's local configuration.

### `two-pack`

`two-pack` is the quick backend workflow. Use it for small tasks that benefit from fast coding without the overhead of Gherkin and acceptance testing, while still preserving backend refactoring and hardening.

- `coder` implements requested behavior with TDD and unit tests.
- `cleaner` batches coder handoffs and performs cleanup, CRAP and DRY review, architectural review, encapsulation and separation-of-concerns fixes, and language mutation hardening.

The normal flow is `coder` -> `cleaner` -> `coder`. Use this branch when you want a tight implementation/refinement loop without specification, QA, property-test, or acceptance-test roles.

### `four-pack`

`four-pack` is the compact specification workflow. Use it for moderate projects that require Gherkin specification and some architectural consideration without splitting every quality gate into its own agent:

- `specifier` turns user intent into precise Gherkin acceptance specifications and asks for approval before handoff.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `refactorer` performs behavior-preserving cleanup, coverage improvement, CRAP and DRY review, mutation-site scans, and property-test support.
- `architect` owns high-level structure, dependency direction, mutation hardening, DRY review, soft Gherkin mutation, and final completion notification.

The normal flow is `specifier` -> `coder` -> `refactorer` -> `architect` -> `specifier`. Use this branch when you want disciplined development without splitting cleanup, architecture, hardening, and QA into separate agents.

### `six-pack`

`six-pack` is the full workflow. Use it for major projects that require full specification, up-front QA, backend verification, and significant architectural consideration. It separates each major quality gate into its own role:

- `specifier` turns user intent into accepted Gherkin specifications and end-to-end QA procedures.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `cleaner` performs local behavior-preserving cleanup, coverage improvement, CRAP and DRY review, and mutation-site scans.
- `architect` reviews module structure, boundaries, dependency direction, and property-test coverage.
- `hardender` performs mutation hardening, language mutation, CRAP and DRY verification, and soft Gherkin mutation.
- `QA` converts the specifier's QA procedures into executable scripts, runs final user-interface verification, checks handoff consistency, and sends completion notifications.

The normal flow is `specifier` -> `coder` -> `cleaner` -> `architect` -> `hardender` -> `QA` -> completion. Use this branch when you want each review and verification concern owned by a separate agent.

## Prerequisites

SwarmForge runs locally. Before starting a runnable branch, make sure the target machine has:

- `zsh`
- `git`
- `tmux`
- Babashka (`bb`)
- At least one configured agent backend, such as `codex`, `claude`, `copilot`, `grok`, or `pi`

## Getting Started

In the directory where you want to use SwarmForge, choose a runnable branch and pull its contents without creating a Git remote:

```sh
BRANCH=four-pack
curl -L "https://github.com/unclebob/swarm-forge/archive/refs/heads/${BRANCH}.tar.gz" | tar -xz --strip-components=1
```

Use `BRANCH=two-pack` for the quick two-agent workflow, `BRANCH=four-pack` for the compact specification workflow, or `BRANCH=six-pack` for the full six-agent workflow. Do not use `main` for this command; `main` is documentary and stores the shared operational scripts, while the runnable branches provide the configurations and prompts intended for projects.

After copying a runnable branch, start the swarm from the target project:

```sh
./swarm
```

The `./swarm` wrapper keeps the runnable branch small. On first use, if `swarmforge/scripts/` is missing, it downloads the `main` branch archive, copies the shared operational scripts from `swarmforge/scripts/`, stages shared constitution articles from `swarmforge/constitution/articles/`, and then launches `swarmforge/scripts/swarmforge.sh`. Later runs reuse the existing local scripts directory instead of overwriting it.

The windows should open automatically.

To stop the swarm, close the first window listed in `swarmforge/swarmforge.conf`. That cleanup window shuts down the tmux sessions and closes the remaining tracked windows.

While a swarm is active, SwarmForge tries to prevent the host from sleeping. On macOS it uses `caffeinate`; on Linux it uses `systemd-inhibit` when available. Display lock or manual sleep can still interrupt agents depending on the OS. Set `SWARMFORGE_PREVENT_SLEEP=0` before `./swarm` to disable this behavior.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge/swarmforge.conf`
- Creates one tmux session per configured role and opens a terminal surface for each role when the selected backend supports it
- Reads behavior from project-local `swarmforge/roles/<role>.prompt` files plus a layered `swarmforge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, `copilot`, or `grok`
- Puts the shared `swarmforge/scripts/` directory on each agent's `PATH`, including handoff helpers for active swarm communication
- Creates git worktrees under `.worktrees/` for roles assigned to dedicated worktree names
- Initializes a git repository in a new working directory when needed
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge/swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarmforge/roles/<role>.prompt` in the working tree being orchestrated.
- **Layered Constitution** — `swarmforge/constitution.prompt` directs agents to read article files under `swarmforge/constitution/articles/`.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, `copilot`, or `grok`.
- **Observable Swarm** — Open one Terminal window per role and watch the sessions in real time.
- **Self-Hosted & Lightweight** — Runs locally in tmux and Terminal with minimal machinery.

## Constitution Structure

Each runnable branch contains a `swarmforge/` directory with this general layout:

```text
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    articles/
      project.prompt
      local-engineering.prompt
      local-workflow.prompt
      ...
  roles/
    <role>.prompt
    ...
```

`constitution.prompt` is the entry point. Runnable branches normally use it to tell agents to read every file in `swarmforge/constitution/articles/`.

Shared default articles live on `main` under:

```text
swarmforge/constitution/articles/
  engineering.prompt
  handoffs.prompt
  workflow.prompt
```

At startup, SwarmForge installs missing shared articles into the runnable branch's `swarmforge/constitution/articles/` directory before creating role worktrees. It also installs missing shared articles into each role worktree during script synchronization. Existing local files are skipped, so a runnable branch can override a shared article by committing an article with the same filename.

Pack-specific additions and exceptions should use explicit local filenames rather than editing shared articles. Current conventions are:

- `project.prompt` for the workflow's project shape and local topology.
- `local-engineering.prompt` for workflow-specific engineering rules.
- `local-workflow.prompt` for workflow-specific flow rules.

The `local-*.prompt` naming convention means "add to or specialize the shared default article for this runnable branch." Use it when the shared article remains valid and the branch only needs extra requirements, exceptions, or narrower instructions. Do not use `local-*.prompt` for a full replacement; use the shared filename instead when the branch intentionally overrides the shared article.

For example, `main` can provide a shared `workflow.prompt`, while `six-pack` can add `local-workflow.prompt` for QA-specific handoff behavior. If a branch needs to replace the shared workflow article completely, it can commit its own `workflow.prompt`; startup will treat that local file as an override and will not copy the shared one over it.

## Roles

Each role in `swarmforge/swarmforge.conf` maps to a corresponding `swarmforge/roles/<role>.prompt` file.

## How It Works

In a runnable branch:

1. SwarmForge reads `swarmforge/swarmforge.conf`.
2. The root `./swarm` wrapper copies shared helper scripts, terminal adapters, and shared constitution articles from the `main` branch when they are not already present.
3. Startup installs missing shared constitution articles into `swarmforge/constitution/articles/`, skipping any local article file that already exists.
4. Startup validates the configured role prompts, helper scripts, and terminal adapters.
5. If the target directory is not already a git repository, startup initializes one and creates the first commit.
6. Startup creates one git worktree per configured role under `.worktrees/`, unless the role is assigned to `master` or `none`.
7. Startup syncs `swarmforge/scripts/` and missing shared constitution articles into each role worktree and puts that local scripts directory on each agent's `PATH`, so agents use local handoff helpers without reaching back into the master checkout.
8. SwarmForge creates tmux sessions, opens terminal windows, and launches each configured backend in its assigned worktree.
9. Startup starts an OS-specific sleep inhibitor when one is available, and cleanup stops it with the swarm.
10. Roles communicate through daemon-delivered handoff files. Agents create validated drafts with `swarm_handoff.sh`, accept work with `ready_for_next.sh`, and complete work with `done_with_current.sh`.

## Handoff Protocol

Startup syncs the shared helper scripts into every role worktree under `swarmforge/scripts/` and puts that local directory on the agent's `PATH`. Agents do not send tmux messages directly. The launcher starts `handoffd.bb`, which owns tmux socket access, watches each agent outbox, copies validated handoff files into recipient inboxes, and sends only generic wake-up notifications.

Agents interact with handoffs through three helper scripts:

- `swarm_handoff.sh <draft-file>` validates and queues outbound handoffs.
- `ready_for_next.sh` accepts work using the role's configured receive mode.
- `done_with_current.sh` completes the current task or batch using the role's configured receive mode.

Outbound drafts use one of two message types. A git handoff points the recipient at a committed state. The commit abbreviation must be exactly 10 hexadecimal characters; `swarm_handoff.sh` validates that it resolves to a single commit and canonicalizes it before queuing the handoff.

```text
type: git_handoff
to: <role>[,<role>...]
priority: NN
task: <short-stable-task-name>
commit: <10-character-commit-abbrev>
```

A note is one short freeform message:

```text
type: note
to: <role>[,<role>...]
priority: NN
message: <one line, max 80 chars>
```

The helper generates the delivered payload. Agents do not write long handoff bodies, branch names, queue filenames, or tmux commands.

Recipient agents run `ready_for_next.sh` when notified or after restart. It dispatches to the task or batch helper configured for that role. If it prints `NO_TASK`, they stop waiting for work. If it prints `TASK: <path>`, they treat the printed `TASK_NAME` and `PAYLOAD` as the task. If it prints `BATCH: <path>`, they process the printed `BATCH_ITEM` entries in helper-delivered order. If a wake-up arrives while an agent is already working, it can ignore the wake-up; `done_with_current.sh` checks for the next task or batch after completing the current work.

The durable handoff files and lifecycle headers replace the old logbook and resend queue. Runtime handoff state lives under `.swarmforge/handoffs/` in each worktree, with `outbox`, `sent`, `failed`, and `inbox` subdirectories. Agents should not hand-edit, merge, stage, or commit handoff runtime state. See [swarmforge/handoff-protocol.md](swarmforge/handoff-protocol.md) for the full protocol.

## The `swarmforge.conf` File

`swarmforge/swarmforge.conf` defines the swarm window-by-window. Each line has this form:

```conf
window <role> <agent> <worktree> [task|batch] [extra-cli-args...]
```

The optional receive mode defaults to `task`. Use `batch` for roles that should consume all currently queued equal-priority handoffs as one batch.

Any fields after the receive mode are passed directly to the agent CLI as additional arguments. If you omit the receive mode, extra arguments may start at the fifth field:

```conf
window coder copilot wt-coder --yolo
window architect claude wt-arch task --dangerously-skip-permissions
```

You can define as many windows as your project needs. Each `role` maps to a corresponding prompt file at `swarmforge/roles/<role>.prompt`, so a config containing `architect`, `coder`, `reviewer`, `research`, and `release` windows would expect:

- `swarmforge/roles/architect.prompt`
- `swarmforge/roles/coder.prompt`
- `swarmforge/roles/reviewer.prompt`
- `swarmforge/roles/research.prompt`
- `swarmforge/roles/release.prompt`

This lets each project choose its own swarm shape instead of being locked to a fixed set of roles.

Example config:

```conf
window coordinator codex master
window coder codex coder
window refactorer codex refactorer
window architect codex architect
```

In the example above, the agents run in these worktrees:

- `coordinator` -> main working directory on `master`, and is the cleanup window because it is listed first
- `coder` -> `.worktrees/coder`
- `refactorer` -> `.worktrees/refactorer`
- `architect` -> `.worktrees/architect`

If a window uses `master` as its worktree name, SwarmForge does not create `.worktrees/master`; that role runs in the main working directory on the `master` branch.

## tmux Behavior

SwarmForge uses a project-specific tmux socket recorded in `.swarmforge/tmux-socket`, so each project swarm is isolated from other tmux sessions. It also honors tmux `base-index` and `pane-base-index` settings when launching agents and sending notifications, so configurations that number windows or panes from `1` work without requiring users to change their tmux preferences.

## Terminal Behavior

SwarmForge opens trackable terminal windows or tabs through a small terminal backend adapter.

Default detection:

- If AppleScript is available, SwarmForge opens macOS Terminal.app windows.
- Otherwise, if `wt.exe` is available, SwarmForge opens Windows Terminal windows.
- Otherwise, SwarmForge attaches the cleanup tmux session in the current shell.

After copying a runnable branch, set `SWARMFORGE_TERMINAL` to override detection:

```sh
SWARMFORGE_TERMINAL=ghostty ./swarm
SWARMFORGE_TERMINAL=terminal-app ./swarm
SWARMFORGE_TERMINAL=windows-terminal ./swarm
SWARMFORGE_TERMINAL=none ./swarm
```

Use `ghostty` when you want SwarmForge to open Ghostty tabs instead of the default Terminal.app windows. Use `windows-terminal` when you want SwarmForge to open Windows Terminal windows from WSL. Use `none` when you want SwarmForge to skip terminal automation and attach the cleanup tmux session in the current shell.

### Adding A Terminal Backend

The shared terminal backends are carried on `main` under `swarmforge/scripts/terminal-adapters/`. Runnable branches copy those scripts at startup. To add a new backend, update `main` by creating one file named after the backend:

```text
swarmforge/scripts/terminal-adapters/wezterm.sh
```

The file must define this small contract:

```sh
terminal_backend_label() {
  echo "WezTerm"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"

  # Open a terminal surface that runs:
  # cd "$WORKING_DIR" && exec tmux -S "$TMUX_SOCKET" attach-session -t "$session"
  #
  # Print a stable window/tab id to stdout.
}

terminal_window_exists() {
  local window_id="$1"

  # Return 0 if the id from terminal_open_session still exists.
  # Return nonzero otherwise.
}

terminal_close_window() {
  local window_id="$1"

  # Close the id from terminal_open_session.
}
```

If the terminal can open sessions but cannot return stable ids for open/check/close, keep `terminal_backend_can_open_sessions` as `return 0` and set `terminal_backend_tracks_windows` to `return 1`. SwarmForge will open one surface per session and skip the watchdog for that backend. `swarmforge/scripts/terminal-adapters/windows-terminal.sh` is an example of this launch-only style.

If the backend cannot open sessions at all, set both capability functions to `return 1`; SwarmForge will attach the cleanup tmux session in the current shell. Only edit `swarmforge/scripts/swarm-terminal-adapter.sh` when adding aliases or changing default auto-detection.

## Window Behavior

Each visible agent window is attached to a tmux session. That means terminal selection, copy, and paste may follow tmux and terminal-emulator rules rather than ordinary text-field behavior. If copy or paste feels unusual, check whether tmux copy mode is active before assuming the agent is stuck.

The first window in `swarmforge.conf` is the cleanup window. Closing that top configured window is the intentional shutdown path: SwarmForge tears down the tmux sessions, closes the remaining tracked windows, and shuts down the swarm.

Closing any other tracked window is non-destructive. The watchdog reopens that window and attaches it back to the same tmux session, so the agent state and terminal history remain intact. This is often the simplest way to recover a window that has landed in an unfamiliar tmux mode or otherwise feels stuck.
