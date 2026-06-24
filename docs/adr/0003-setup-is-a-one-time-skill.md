---
status: accepted
---

# Setup is a one-time skill, not in-execution work

Adapting a project to the swarm — installing the project's language quality tools (mutation, CRAP, DRY, the Acceptance Pipeline commands), enabling session tracking, and granting the permissions the agents need — lives in a **`setup-swarm` skill** that ships inside the swarm install. It is the operator's *first* action on a project (`/setup-swarm`); the run path does no project provisioning. (Installing the swarm's *own* pinned `entire` skills is launcher bootstrap, not project setup — that belongs to ADR 0018.)

**Setup runs first; the run path only guards.** `setup-swarm` writes a **swarm-ready marker** (`.swarmforge/setup-complete`) when it finishes. `./swarm` checks that marker before launching any role and, if it is absent, refuses and tells the operator to run `setup-swarm` first — it never runs setup itself. (An earlier design had `./swarm` auto-run setup on first launch; that is superseded — setup is an explicit operator step and the launcher merely verifies it happened.) `./swarm` still fetches its own code when missing, bootstraps its own pinned skills (ADR 0018), and does per-launch plumbing (worktrees, sessions, copying constitution files); it never adapts the *project* to its stack. The operator deletes the marker to force a re-run.

**The only edits to upstream files are four role-prompt lines.** The "At startup, install the language tools" directives in `coder`, `QA`, `cleaner`, and `hardender` are removed; that install work moves into the setup skill and runs once. ADR 0002 already removes these same lines for the idle gate (a role does nothing until handed off); here they go for a second, complementary reason — tool install is a one-time setup step, not per-task startup work. The removal is the seam between the two decisions; neither owns it alone.

**Why a skill rather than functions added to the launch script.** A skill is a new fork-owned file, so it adds zero upstream merge-conflict surface — exactly the additive divergence ADR 0001 asks for. Adding setup functions inside `swarmforge.sh` would instead edit an upstream-tracked file, a permanent conflict point on every sync. A skill also lets setup *reason about the stack* (which tools for Go vs Java vs Clojure, which gates matter), which a deterministic script cannot.

**Why replace rather than overlay.** Setup is an explicit one-time step; the run path stays pure "start the agents." The accepted cost is that the swarm no longer self-installs project tooling on first run — the operator runs the setup skill once before the first `./swarm`. Any setup step this moves out of the run path is named and documented so the divergence stays auditable.

**Setup also lays down the project scaffold.** Beyond tooling, the skill writes the one-time repository scaffold the swarm assumes: a `.gitignore` covering the swarm's runtime artifacts (`logbook.jsonl`, `tmp/`, `.swarmforge/`), the project's default branch probed once (`git symbolic-ref refs/remotes/origin/HEAD`) and recorded in `swarmforge.conf`, and a small, targeted set of permission allow-rules in `.claude/settings.json` (for example `Bash(gh pr merge*)` for the integrator, `Bash(git reset --hard origin/<default-branch>)` for the specifier). Under autonomous permission mode (ADR 0019) those allow-rules are advisory hints rather than a load-bearing whitelist, so the set is kept deliberately small.

## Pending implementation

- The `setup-swarm` skill, shipped at `swarmforge/skills/setup-swarm/` (mirroring `agent-retro`): it reasons about the stack and writes the project tooling, session tracking (`entire enable …` plus `entire agent add <backend>` per `swarmforge.conf` backend), the permission allow-rules, and the `.gitignore`/default-branch scaffold, then writes the marker. *How* it detects the stack is the skill's own domain — deliberately not prescribed here, since reasoning about the stack is the whole reason setup is a skill and not a script.
- `main`: `./swarm` checks `.swarmforge/setup-complete` before launching roles and refuses (with a message to run `setup-swarm`) if it is absent.
- The `entire` skill install is **not** part of this skill — it is launcher bootstrap (ADR 0018).
