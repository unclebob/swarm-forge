<p align="center" style="color: red; font-weight: bold; font-size: 2em; font-style: italic; text-decoration: underline;">
Do not spend any money on a bankrbot SWARM token.
</p>

# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

This `main` branch is documentary: it explains the system and carries the shared operational scripts. The runnable `four-pack` and `six-pack` branches carry the project-facing configurations and role prompts that define specific workflows.

SwarmForge is an agent coordination system that facilitates communication between agents working in different git worktrees.

It provides a shared structure for role-specific prompts, worktree assignment, tmux sessions, and message passing so multiple agents can collaborate on the same project without stepping on each other.

## Branches

The runnable SwarmForge configurations live on dedicated branches. Each branch contains the `swarmforge/swarmforge.conf`, constitution, and role prompts for one workflow. At startup, its `./swarm` wrapper copies the shared operational scripts from `main` when they are not already present, then launches that branch's local configuration.

### `four-pack`

`four-pack` is the compact workflow. It keeps the swarm small while preserving a complete delivery path:

- `specifier` turns user intent into precise Gherkin acceptance specifications and asks for approval before handoff.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `refactorer` performs behavior-preserving cleanup, coverage improvement, CRAP and DRY review, mutation-site scans, and property-test support.
- `architect` owns high-level structure, dependency direction, mutation hardening, DRY review, soft Gherkin mutation, and final completion notification.

The normal flow is `specifier` -> `coder` -> `refactorer` -> `architect` -> `specifier`. Use this branch when you want disciplined development without splitting cleanup, architecture, hardening, and QA into separate agents.

### `six-pack`

`six-pack` is the full workflow. It separates each major quality gate into its own role:

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
- At least one configured agent backend, such as `codex`, `claude`, `copilot`, or `grok`

## Getting Started

In the directory where you want to use SwarmForge, choose a runnable branch and pull its contents without creating a Git remote:

```sh
BRANCH=four-pack
curl -L "https://github.com/unclebob/swarm-forge/archive/refs/heads/${BRANCH}.tar.gz" | tar -xz --strip-components=1
```

Use `BRANCH=six-pack` instead when you want the six-agent workflow. Do not use `main` for this command; `main` is documentary and stores the shared operational scripts, while the runnable branches provide the configurations and prompts intended for projects.

After copying a runnable branch, start the swarm from the target project:

```sh
./swarm
```

The `./swarm` wrapper keeps the runnable branch small. On first use, if `swarmforge/scripts/` is missing, it downloads the `main` branch archive, copies the shared operational scripts from `swarmforge/scripts/`, and then launches `swarmforge/scripts/swarmforge.sh`. Later runs reuse the existing local scripts directory instead of overwriting it.

The windows should open automatically.

To stop the swarm, close the first window listed in `swarmforge/swarmforge.conf`. That cleanup window shuts down the tmux sessions and closes the remaining tracked windows.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge/swarmforge.conf`
- Creates one tmux session per configured role and opens a terminal surface for each role when the selected backend supports it
- Reads behavior from project-local `swarmforge/<role>.prompt` files plus a layered `swarmforge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, `copilot`, or `grok`
- Creates a project-local `swarmtools/` directory with notification helpers for the active swarm
- Creates git worktrees under `.worktrees/` for roles assigned to dedicated worktree names
- Initializes a git repository in a new working directory and creates a first commit with `logs/` and `agent_context/` ignored
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge/swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarmforge/<role>.prompt` in the working tree being orchestrated.
- **Layered Constitution** — `swarmforge/constitution.prompt` can delegate to subordinate files such as `swarmforge/constitution/project.prompt`, `engineering.prompt`, and `workflow.prompt`.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, `copilot`, or `grok`.
- **Observable Swarm** — Open one Terminal window per role and watch the sessions in real time.
- **Self-Hosted & Lightweight** — Runs locally in tmux and Terminal with minimal machinery.

## Constitution And Roles

Each runnable branch contains a `swarmforge/` directory with this general layout:

```text
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    project.prompt
    engineering.prompt
    workflow.prompt
  <role>.prompt
  ...
```

`constitution.prompt` is the entry point. It can define precedence and direct agents to read subordinate constitution files in order. That lets you separate project-specific rules from engineering rules and workflow rules without forcing everything into one large prompt.

Each role in `swarmforge/swarmforge.conf` maps to a corresponding `swarmforge/<role>.prompt` file.

## How It Works

In a runnable branch:

1. SwarmForge reads `swarmforge/swarmforge.conf`.
2. The root `./swarm` wrapper copies shared helper scripts and terminal adapters from the `main` branch when `swarmforge/scripts/` is not already present.
3. Startup validates the configured role prompts, helper scripts, and terminal adapters.
4. If the target directory is not already a git repository, startup initializes one and creates the first commit.
5. Startup creates one git worktree per configured role under `.worktrees/`, unless the role is assigned to `master` or `none`.
6. Startup creates `swarmtools/notify-agent.sh` for handoffs between agents.
7. SwarmForge creates tmux sessions, opens terminal windows, and launches each configured backend in its assigned worktree.
8. Roles communicate through handoff files and `notify-agent.sh`.

## The `swarmforge.conf` File

`swarmforge/swarmforge.conf` defines the swarm window-by-window. Each line has this form:

```conf
window <role> <agent> <worktree>
```

You can define as many windows as your project needs. Each `role` maps to a corresponding prompt file at `swarmforge/<role>.prompt`, so a config containing `architect`, `coder`, `reviewer`, `research`, and `release` windows would expect:

- `swarmforge/architect.prompt`
- `swarmforge/coder.prompt`
- `swarmforge/reviewer.prompt`
- `swarmforge/research.prompt`
- `swarmforge/release.prompt`

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

## Multiplexer Backend (tmux vs cmux)

SwarmForge runs each role under a terminal multiplexer, selected by `SWARM_MUX`:

- `tmux` (default) — tmux is the multiplexer. One tmux session per role on the project-specific socket, surfaced through a terminal backend adapter (see *Terminal Behavior* below). This is the standard path.
- `cmux` — [cmux](https://cmux.sh) is the multiplexer; there is **no tmux**. Each role runs natively in its own cmux workspace, and all role workspaces are collected under one `SwarmForge · <project>` workspace group in the sidebar. Use this when running inside cmux, where tmux behaves unreliably.

Detection: an explicit `SWARM_MUX=tmux|cmux` always wins. Otherwise SwarmForge auto-detects — if it is launched from inside a cmux workspace (any of `CMUX_SOCKET_PATH`, `CMUX_SOCKET`, `CMUX_WORKSPACE_ID`, `CMUX_SURFACE_ID` is set) it uses `cmux`, else `tmux`.

```sh
SWARM_MUX=cmux ./swarm     # force the cmux multiplexer (must be run from inside cmux)
SWARM_MUX=tmux ./swarm     # force tmux even when inside cmux
```

In `cmux` mode the terminal backend adapter, the project tmux socket, and the window watchdog are **not used** — cmux owns workspace creation, handoff delivery (`cmux send`), and teardown (`cmux workspace-group delete`). `swarm stop` reads `.swarmforge/mux-backend` to tear down the right way. The rest of this section (*Terminal Behavior*) applies to `tmux` mode only.

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

## Examples

The repository includes example swarm definitions under `examples/`.

- `examples/clojureHTW/swarmforge/` shows a layered constitution and agent prompts for a Clojure Hunt The Wumpus project, including a queueing rule for messages that arrive while an agent is busy.

These examples are documentation references only. Start real projects from the `four-pack` or `six-pack` branches so the project receives a complete, runnable SwarmForge configuration.
