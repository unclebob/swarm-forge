---
status: accepted
---

# `.agents/` knowledge contract injected into every bundle

Promoted knowledge is worthless if it never reaches the agent that needs it. Upstream bundles only the constitution and the role prompt into an agent's context, so there is no channel for project-specific, accumulated knowledge. The fork defines a versioned knowledge contract in the project repo and **injects it into every role bundle at launch**, closing the loop the curator (ADR 0013) feeds.

**The contract lives in the project repo, under `.agents/` plus a root `AGENTS.md`.** `AGENTS.md` is the navigation map and universal invariants (≤ 60 lines); `.agents/roles/<role>.md` is one role's operational knowledge (≤ 40 lines); `.agents/references/<topic>.md` holds deep dives reached by pointer; `.agents/skills/<name>/` holds promoted procedures; `.agents/backlog.md` is the enforcement-gate backlog; `.agents/ledger.md` is the append-only audit. All of it is written only by the curator and **versioned in the project**, not in `~/.claude` — so a fresh clone carries every promoted lesson and nothing depends on a machine's local memory.

**Injection is automatic and role-scoped.** When the launcher builds a role's bundle it appends, when the files exist, the root `AGENTS.md` (so every role gets the universal invariants) and that role's `.agents/roles/<role>.md` (so a role gets only its own operational knowledge). References are not injected — they load on demand when an included line points to them, which is why every reference must be pointed at from `AGENTS.md` or a role file. Missing files are silently skipped: a project that has not bootstrapped its knowledge yet launches cleanly with no knowledge blocks.

## Pending implementation

- `main`: extend the bundle generator (`write_agent_instruction_file` in `swarmforge.sh`) to append `AGENTS.md` and `.agents/roles/<role>.md` from the project root when present, and add the preamble sentence telling the agent these knowledge files (and on-demand references) are included.
- Acceptance: a scratch project with an `AGENTS.md` → every generated bundle carries it; adding `.agents/roles/coder.md` → only the coder's bundle gains it; removing both → bundles generate with no knowledge blocks and no errors.
- Pairs with ADR 0013 (the curator is the only writer of this contract).
