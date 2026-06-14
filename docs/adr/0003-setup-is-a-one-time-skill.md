---
status: accepted
---

# Setup is a one-time skill, not in-execution work

Adapting a project to the swarm — installing the project's language quality tools (mutation, CRAP, DRY, the Acceptance Pipeline commands), enabling session tracking, granting the permissions the agents need, pinning skill versions — lives in a **setup skill** that ships inside the swarm install and is the first thing the operator runs. The run path does no project setup.

**Execution installs nothing.** `./swarm` still fetches its own code when missing (the program obtaining itself, not project setup) and still does per-launch plumbing (worktrees, sessions, copying constitution files). It never adapts the project to its stack. If the project has not been set up, `./swarm` stops and says so rather than installing anything.

**The only edits to upstream files are four role-prompt lines.** The "At startup, install the language tools" directives in `coder`, `QA`, `cleaner`, and `hardender` are removed; that install work moves into the setup skill and runs once. ADR 0002 already removes these same lines for the idle gate (a role does nothing until handed off); here they go for a second, complementary reason — tool install is a one-time setup step, not per-task startup work. The removal is the seam between the two decisions; neither owns it alone.

**Why a skill rather than functions added to the launch script.** A skill is a new fork-owned file, so it adds zero upstream merge-conflict surface — exactly the additive divergence ADR 0001 asks for. Adding setup functions inside `swarmforge.sh` would instead edit an upstream-tracked file, a permanent conflict point on every sync. A skill also lets setup *reason about the stack* (which tools for Go vs Java vs Clojure, which gates matter), which a deterministic script cannot.

**Why replace rather than overlay.** Setup is an explicit one-time step; the run path stays pure "start the agents." The accepted cost is that the swarm no longer self-installs project tooling on first run — the operator runs the setup skill once before the first `./swarm`. Any setup step this moves out of the run path is named and documented so the divergence stays auditable.

## Considered options

- **Add setup as functions inside `swarmforge.sh`** — rejected: edits an upstream-tracked file (a permanent merge-conflict surface, against ADR 0001's additive rule) and a deterministic script cannot adapt to the project's stack.
- **Overlay — skill adds the fork's extras while execution keeps installing** — rejected: leaves setup split across two places and keeps the run path doing setup work, defeating the purpose.

## Pending implementation

- The skill itself: stack detection, the exact tooling/permissions/pins it writes, how it is shipped inside the install, and the "swarm-ready" marker `./swarm` checks before launching.
