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

In the example above, the agents run in these worktrees:

- `coordinator` -> main working directory on `master`, and is the cleanup window because it is listed first
- `coder` -> `.worktrees/coder`
- `refactorer` -> `.worktrees/refactorer`
- `architect` -> `.worktrees/architect`

If a window uses `master` as its worktree name, SwarmForge does not create `.worktrees/master`; that role runs in the main working directory on the `master` branch.

## Skills

Agents launched with the `claude` backend inherit every Claude Code skill installed for the current user (global `~/.claude/skills/` and any plugins). This means role prompts can instruct an agent to invoke specific skills at specific moments — for example, telling a coder to run the `simplify` skill before handoff, or telling a reviewer to run `pr-review-toolkit:code-reviewer`, `pr-review-toolkit:silent-failure-hunter`, `pr-review-toolkit:pr-test-analyzer`, and similar.

A few things to keep in mind:

- Skills are a Claude Code feature. Agents launched with the `codex` backend do not have access to skills, and any skill instructions in their prompt will be ignored.
- Skill availability depends on what the user running SwarmForge has installed. If a prompt names a skill that is not installed, tell the agent to note it in its handoff rather than skip silently so drift is visible.
- Role prompts are the right place to wire skills in. Put skill invocation rules near the handoff step of the role that owns that quality gate (coder for pre-handoff cleanup, reviewer for verification).

See `examples/clojureHTW-pairs/swarmforge/coder.base.prompt` and `reviewer.base.prompt` for a worked example of skill usage in role prompts.

## Examples

The repository includes example swarm definitions under `examples/`.

- `examples/generic/swarmforge/` is a stack-agnostic starter: a layered constitution with behavioral engineering rules adapted for parallel-worktree swarms, plus role prompts for architect/coder/reviewer that don't lock you into a specific test framework.
- `examples/clojureHTW/swarmforge/` shows a layered constitution and agent prompts for a Clojure Hunt The Wumpus project, including a queueing rule for messages that arrive while an agent is busy.
- `examples/clojureHTW-pairs/swarmforge/` extends the same project with two parallel coder/reviewer pairs (`coder-a`+`reviewer-a`, `coder-b`+`reviewer-b`) coordinated by a single architect. Use this layout when you want slices of work to run in parallel and the architect to serialize merges back to `master`.

Use these example directories as starting points for project-local `swarmforge/` folders.

## Getting Started

- In the directory where you want to use SwarmForge, pull the repository contents without creating a Git remote:

  ```sh
  curl -L https://github.com/unclebob/swarm-forge/archive/refs/heads/main.tar.gz | tar -xz --strip-components=1
  ```
	
## Running SwarmForge

Just type `swarm`. The windows should all pop up.

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
