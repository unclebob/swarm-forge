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
- Creates a project-local `swarmtools/` directory with notification helpers for the active swarm
- Creates one git worktree per configured role under `.worktrees/`
- Initializes a git repository in a new working directory and creates a first commit with `logs/` and `agent_context/` ignored
- Keeps all swarm state local to the working directory in `.swarmforge/`

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
5. Run `swarmforge.sh <working-directory>` or run it from inside that directory.
6. If the working directory is not already a git repo, startup runs `git init`, renames the initial branch to `master`, writes `.gitignore` entries for `.swarmforge/`, `.worktrees/`, `swarmtools/`, `logs/`, and `agent_context/`, and makes the first commit from the current project state.
7. Startup creates a git worktree for each window under `.worktrees/<worktree>`, unless the worktree field is `none` or `master`.
8. Startup creates `swarmtools/notify-agent.sh` for that project.
9. SwarmForge creates tmux sessions, opens Terminal windows, and launches each configured backend in its assigned worktree.
10. Roles communicate through helper commands such as `notify-agent.sh`.

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

Example config:

```conf
window architect claude master
window coder codex coder
window reviewer codex reviewer
window logger none none
```

`logger` is a utility role. When configured with `none`, it tails `logs/agent_messages.log`.

In the example above, the agents run in these worktrees:

- `architect` -> main working directory on `master`
- `coder` -> `.worktrees/coder`
- `reviewer` -> `.worktrees/reviewer`
- `logger` -> main working directory

If a window uses `master` as its worktree name, SwarmForge does not create `.worktrees/master`; that role runs in the main working directory on the `master` branch.

## Examples

The repository includes example swarm definitions under `examples/`.

- `examples/clojureHTW/swarmforge/` shows a layered constitution and agent prompts for a Clojure Hunt The Wumpus project, including a queueing rule for messages that arrive while an agent is busy.

Use these example directories as starting points for project-local `swarmforge/` folders.

## Getting Started

- Clone this repository and make `swarmforge.sh` executable.
- Add the directory containing `swarmforge.sh` to your shell `PATH`.
- Create or choose the project directory you want SwarmForge to manage.
- Inside that project, create a `swarmforge/` directory.
- Create `swarmforge/swarmforge.conf` and define the windows for your swarm.
- Use the earlier `Constitution And Roles`, `How It Works`, and `The swarmforge.conf File` sections as the reference for the expected prompt layout, role files, and window definitions.
- Type `swarmforge`.

## Context Hygiene For Long-Running Swarms

Agents accumulate context across every slice. After dozens of slices, sessions can run hundreds of thousands of tokens deep — expensive per call, and a real risk for subtle drift (forgetting earlier rules, conflating patterns across slices, repeating corrected mistakes).

Two disciplines keep the swarm performant without losing useful continuity:

- **`/compact` between sub-slices.** After each slice merges and both agents are idle, send `/compact` via `notify-agent.sh`. The agent's harness summarizes verbose intermediate tool-call and test-output noise while preserving the high-signal context — constitution rules, established patterns, reviewer-flagged carry-forwards.
- **`/clear` at phase boundaries.** When a major plan phase closes, send `/clear` before the first slice of the next phase. The agent re-reads its role prompt and the constitution on its next prompt and bootstraps fresh.

Within a slice, do nothing — mid-slice continuity matters; let the agent finish.

If a `/compact` visibly drops important context (agent forgets a carry-forward, references a wrong pattern), escalate to `/clear` immediately.

Both are slash commands the agent harness interprets in its conversation buffer:

```bash
swarmtools/notify-agent.sh coder "/compact"
swarmtools/notify-agent.sh reviewer "/compact"
```

When a backend doesn't expose `/compact` / `/clear` as slash commands, the guidance applies in spirit; check your harness for equivalents.
