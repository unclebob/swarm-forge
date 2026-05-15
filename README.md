# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

SwarmForge is an agent coordination system that facilitates communication between agents working in different git worktrees.

It provides a shared structure for role-specific prompts, worktree assignment, tmux sessions, and message passing so multiple agents can collaborate on the same project without stepping on each other.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge/swarmforge.conf`
- Creates one tmux session and one Terminal window per configured role
- Reads behavior from project-local `swarmforge/<role>.prompt` files plus a layered `swarmforge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, or `none`
- Supports **multiple concurrent swarms per project**, each isolated under its own instance ID
- Creates a per-instance `notify-agent.sh` helper inside the instance's state directory
- Creates one git worktree per configured role under `.worktrees/<instance-id>/`
- Initializes a git repository in a new working directory and creates a first commit with `logs/` and `agent_context/` ignored
- Keeps all swarm state local to the working directory in `.swarmforge/instances/<instance-id>/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge/swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarmforge/<role>.prompt` in the working tree being orchestrated.
- **Layered Constitution** — `swarmforge/constitution.prompt` can delegate to subordinate files such as `swarmforge/constitution/project.prompt`, `engineering.prompt`, and `workflow.prompt`.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, or no agent at all.
- **Observable Swarm** — Open one Terminal window per role and watch the sessions in real time.
- **Self-Hosted & Lightweight** — Runs locally in tmux and Terminal with minimal machinery.

## Constitution And Roles

In a configuration with an `architect`, `coder`, and `reviewer`, the recommended prompt layout is:

```text
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    project.prompt
    engineering.prompt
    workflow.prompt
  architect.prompt
  coder.prompt
  reviewer.prompt
```

`constitution.prompt` is the entry point. It can define precedence and direct agents to read subordinate constitution files in order. That lets you separate project-specific rules from engineering rules and workflow rules without forcing everything into one large prompt.

The default three-agent workflow is:

- `architect` defines behavior, plans, and acceptance-level intent
- `coder` implements one small slice at a time and hands off completed work
- `reviewer` performs deeper verification and quality checks before final handoff

`logger` remains an optional utility role with no agent backend.

## How It Works (High Level)

1. Create a `swarmforge/` directory in the target working directory.
2. Put `swarmforge.conf`, `constitution.prompt`, and one `<role>.prompt` file per configured role inside it. If needed, add subordinate files under `swarmforge/constitution/`.
3. In `swarmforge/swarmforge.conf`, define each window as `window <role> <agent> <worktree>`.
4. Add `swarmforge.sh` to your shell `PATH` before startup.
5. Run `swarmforge.sh <working-directory> [--name <instance-id>]` or run it from inside that directory. Each launch creates a fresh **swarm instance**; pass `--name` to assign a memorable ID (re-launching with the same name replaces that instance). Without `--name`, a random 6-hex ID is generated.
6. If the working directory is not already a git repo, startup runs `git init`, renames the initial branch to `master`, writes `.gitignore` entries for `.swarmforge/`, `.worktrees/`, `logs/`, and `agent_context/`, and makes the first commit from the current project state.
7. Startup creates a git worktree for each window under `.worktrees/<instance-id>/<worktree>` on branch `swarmforge-<instance-id>-<worktree>`, unless the worktree field is `none` or `master`.
8. Startup creates `.swarmforge/instances/<instance-id>/swarmtools/notify-agent.sh` scoped to that instance.
9. SwarmForge creates tmux sessions (named `swarmforge-<project-hash>-<instance-id>-<role>`), opens Terminal windows titled `SwarmForge [<instance-id>] <Role>`, and launches each configured backend in its assigned worktree.
10. Roles communicate through helper commands such as `notify-agent.sh` — the absolute path is injected into each agent's launch prompt.

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

This lets each project choose its own swarm shape instead of being locked to a fixed set of roles. The only special case is a utility role such as `logger` using the `none` backend, which opens a window without launching an agent.

The first window in the config is the cleanup window. SwarmForge attaches shutdown cleanup to that window's launch command and falls back to that tmux session when Terminal automation is unavailable.

When SwarmForge opens Terminal windows, it also starts a small window watchdog:

- Closing a non-cleanup Terminal window reopens that window attached to the same tmux session.
- Closing the cleanup Terminal window shuts down all configured tmux sessions and closes the remaining tracked Terminal windows.
- The watchdog updates `.swarmforge/window-ids` when it reopens a window so shutdown cleanup still targets the current windows.

Example config:

```conf
window coordinator codex master
window coder codex coder
window refactorer codex refactorer
window architect codex architect
```

`logger` is a utility role. When configured with `none`, it tails `logs/agent_messages.log`.

In the example above, the agents run in these worktrees (with instance ID `<id>`):

- `coordinator` -> main working directory on `master`, and is the cleanup window because it is listed first
- `coder` -> `.worktrees/<id>/coder`
- `refactorer` -> `.worktrees/<id>/refactorer`
- `architect` -> `.worktrees/<id>/architect`

If a window uses `master` as its worktree name, SwarmForge does not create a worktree directory for it; that role runs in the main working directory on the `master` branch. Note that all instances of the same project share the `master` worktree.

## Examples

The repository includes example swarm definitions under `examples/`.

- `examples/clojureHTW/swarmforge/` shows a layered constitution and agent prompts for a Clojure Hunt The Wumpus project, including a queueing rule for messages that arrive while an agent is busy.

Use these example directories as starting points for project-local `swarmforge/` folders.

## Getting Started

- In the directory where you want to use SwarmForge, pull the repository contents without creating a Git remote:

  ```sh
  curl -L https://github.com/unclebob/swarm-forge/archive/refs/heads/main.tar.gz | tar -xz --strip-components=1
  ```
	
## Running SwarmForge

Just type `swarm`. The windows should all pop up.

To run multiple swarms in the same project simultaneously, give each one a `--name`:

```sh
swarm --name auth-refactor
swarm --name perf-pass
```

Each named instance gets its own tmux sessions, Terminal windows, worktrees, and state directory under `.swarmforge/instances/<name>/`.
