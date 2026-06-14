---
status: accepted
---

# Curator role and the knowledge-promotion loop

Upstream ends the line at QA: the specifier merges and asks for the next feature, and whatever the run *learned* — a wrong path taken, a convention discovered, a gate that should have existed — lives only in a session retro that no one reads again. The fork adds a terminal **curator** role, after the integrator, that turns those retros into **versioned repo knowledge** via one self-merging PR per run, then releases the specifier for the next feature.

**Pipeline position: integrator → curator → specifier.** The integrator notifies the curator on a green landing; the curator promotes the run's knowledge and only then notifies the specifier. An empty run (no unprocessed retros) notifies the specifier immediately with no PR — the line never stalls on the curator. The curator makes no code changes; it may only write `AGENTS.md` and files under `.agents/` (ADR 0014).

**Capture everything; discard once, at the curator.** The retro skill tags every action with a scope — `project | swarmforge | skill | ephemeral` — and captures all of them without filtering for "obviousness." The single discard gate is the curator's **non-inferable check**: could a future agent reach this fix from the error output and the files it names, with no foreknowledge? If yes, it is not worth promoting. Putting the one filter here, not at capture, means nothing is lost before a consistent judge sees it.

**Promote to the highest rung that fits (the routing ladder).** A mechanical fix (config line, CI gate, script guard) goes to the enforcement-gate backlog — a gate beats documentation. Otherwise: `AGENTS.md` for universal invariants, `.agents/roles/<role>.md` for one role's operational knowledge, `.agents/references/<topic>.md` for deep dives (each needs a pointer line or it never loads), `.agents/skills/<name>/` only on the second occurrence of a need, `.agents/upstream/<date>.md` for `swarmforge`-scoped items, ledger-only for ephemeral and rejected. A learning whose fix is global routes *up* the ladder, never into `AGENTS.md`, and is discarded only when the gap is already mechanically closed. Every item is rewritten from a phenomenon ("X can fail because Y") into a rule ("every X MUST Z because Y") before it is promoted.

**The ledger is the append-only audit.** `.agents/ledger.md` records one never-pruned line per processed item — `date | session-id | role | failure-class | verdict` — so recurrence is provable: an item rejected before and now recurring has proven itself non-trivial and is promoted rather than rejected again.

**The curator self-merges from day one.** The knowledge PR is merged in-role with no user confirmation; the PR body (a metric line plus one verbatim bullet per promoted rule) and the ledger are the asynchronous review surface. Budgets hold the knowledge small: `AGENTS.md` ≤ 60 lines, each role file ≤ 40 — over budget, the stalest or now-inferable lines are pruned and ledgered.

**Loop health is self-reported.** Each PR body carries running totals (`promoted | rejected | upstream | ephemeral`). Kill criterion: fewer than three promotions that survive contact with later sessions over 90 days → disable the curator window; the ledger and promoted docs stay.

**Retros are captured automatically, from the transcript, before idle.** The loop only has something to promote because every role runs `agent-retro` as its last step before going idle — a line added to every role prompt — so a retro is produced for each role-session with no one asking. The skill reconstructs the session from its transcript rather than the role's from-memory account: it extracts via the `entire` CLI (`entire session current` → `session info --transcript`), falling back to Claude Code's `~/.claude/projects/` transcript path when `entire` is absent. Grounding the retro in the transcript is what lets the curator (and `retro-triage`, ADR 0021) judge against what actually happened, not what the role remembers happening.

## Pending implementation

- `six-pack` (four-pack is frozen per ADR 0001 / the change manifest): new `curator` role prompt; `swarmforge.conf` gains the curator window (last); rewire — integrator notifies the curator, specifier waits on the curator before the next feature, `workflow.prompt` documents the integrator→curator→specifier chain.
- `main`: upgrade the `agent-retro` skill — scope tag on every action, capture-first (no pre-filter), and an autonomous mode that marks actions `pending-curation` without prompting a human.
- `main`: `agent-retro` transcript capture (`entire session current` → `session info --transcript`, with the `~/.claude/projects/` fallback); add the "run `agent-retro` before going idle" line to every role prompt. Source: `feat/issue-20-a-retro-skill-upgrade`.
- Pairs with ADR 0014 (the `.agents/` contract the curator writes and the launcher injects).
