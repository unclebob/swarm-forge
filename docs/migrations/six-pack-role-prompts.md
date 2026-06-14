# Migration recovery — six-pack role prompts

Per-role recovery for `swarmforge/roles/*.prompt`. Base = `upstream/six-pack`. **Re-merge deltas onto current upstream prompts; do not copy whole backup files** (they predate upstream and carry content ADRs reversed — see STRIP table). Primary source = `backup/six-pre-reset` unless noted.

Universal add to **every** role prompt: idle-gate line `"Wait for a handoff. Do not act without one."` (0002) and `"Run agent-retro before going idle."` Back-routing (0004) general rule has **no backup source** — author fresh from ADR 0004 wherever a role needs it (structural finding → origin stage once; local → fix in place; single-finding back-once cap).

## Existing roles — deltas

| Role | Re-merge (recover-from `backup/six-pre-reset` unless noted) | STRIP / fix |
|------|------------------------------------------------------------|-------------|
| **coder** | idle-gate; UX-Intent read line (0007); handoff `notify cleaner`→`notify ux-engineer` (0007) | STRIP `## Acceptance Pipeline` block (upstream L8–11, the "At startup… APS" bullets) (0003) |
| **QA** ⚠ | idle-gate; **0010** surface-harness: L13 "through the user interface only"→"through the project surface harness only" + Expected-bullet→assertion/`NOT AUTOMATED` rule + re-execute `observation-harness/` + route-back-if-missing; handoff →`notify integrator` (0008) | STRIP `## Startup Tools` (L7) (0003); `logbook.json`→keep upstream `logbook.jsonl`. **0005 refute posture has NO backup source — author fresh**, replacing L14 "Fix bugs found by the QA suite…" with structural→route-back / local→fix-in-place. Merge 0005 (new) + 0010 (backup) into one prompt. |
| **cleaner** | idle-gate; **0016** boundary-file scan (>15 mutation sites → extract) + stripped-view-as-untested anti-pattern (cleanest source: `feat/baseline-scenarios-six`) | STRIP `At startup, install…` (L19) (0003) |
| **hardender** | idle-gate; rendering-invariant property-test line (L18 — **unmanifested divergence**, see note) | STRIP `## Startup Tools` (L8–9) (0003). STRIP backup's `"merge all queued architect handoffs together"` — **unauthorized, no ADR**; keep upstream's "batch in sorted filename order". |
| **specifier** ⚠ | idle-gate; **0008** worktree reset `git reset --hard origin/<default-branch>` via `git symbolic-ref` (recover from `feat/six-pack-pipeline-order-and-scaffold`, NOT backup); **0008** handoff L36 "merge the changes and ask the user"→"When the curator notifies you… ask the user for the next feature"; **0007** UX-Intent authoring; **0009** start from template + "seven"→**"eight"**; **0011** read dependency-manifest + propose-on-undeclared (recover from `backup`/`feat/issue-20-c`, NOT pipeline-order which dropped it) | STRIP DESIGN.md walk-up + scaffold-on-absence (0007); STRIP backup's `git merge --ff-only origin/master` startup (0008, also hardcodes `master`) |

⚠ **QA and specifier are the complex merges** — multiple overlapping layers, several from different branches. Apply carefully.

## STRIP / STALE table (backup content ADRs reversed)
| Stale content | In | Reversed by |
|---------------|-----|-------------|
| DESIGN.md walk-up + scaffold | specifier, ux-engineer | ADR 0007 (reference-from-feature-file only) |
| "seven header sections" | specifier | ADR 0009 (six-pack = eight) |
| `git merge --ff-only origin/master` startup | specifier | ADR 0008 (specifier stops merging; `master` stale) |
| "merge all queued architect handoffs together" | hardender | no ADR — keep upstream sorted-batch |
| `logbook.json` | QA | upstream renamed → `logbook.jsonl` |
| curator budgets 150/300 | curator | ADR 0013 + locked spec = 60/40 |

## New roles (net-new files)

### ux-engineer (ADR 0007) — recover `backup/six-pre-reset:swarmforge/roles/ux-engineer.prompt` (≡ `origin/feat/obs-harness-six`; NOT pipeline-order/baseline which lack the `observation-harness/` commit step)
Outline: identity+idle · skip if no `## UX Intent` (→notify cleaner) · UX-Intent verification across Visual Composition/Information Hierarchy/Interaction Feel/State Transitions by running the binary · fix rendering only (back-route to coder for model-state, N=3) · durable artifacts: golden snapshots + rendering invariants + `observation-harness/` scenarios via surface tool · run test suite · `## Visual quality standards` (AI-aesthetic anti-patterns, type hierarchy, WCAG 4.5:1/3:1) · notify cleaner.
**STRIP:** DESIGN.md walk-up; make DESIGN.md fix-authority conditional on a feature-file reference (not tree discovery).

### integrator (ADR 0008) — recover `backup/six-pre-reset:swarmforge/roles/integrator.prompt` (≡ `feat/issue-20-c`; NOT baseline-scenarios-six which still says "notify specifier")
Outline: identity+idle · own landing, one PR/feature, autofix-lint-only · steps: receive from QA → branch `feat/<initiative>` → `gh pr create` → watch CI → green: `gh pr merge --squash --delete-branch` + post-merge gate → **notify curator** → CI-red routing (tests→coder, coverage/CRAP/DRY→cleaner, arch→architect; autofix doesn't count; N=3 then `FAILED: depth cap reached`) → agent-retro.
**FIX (locked spec wins):** step 7 must add "Include the specifier handoff name and the post-merge master commit hash."

### curator (ADR 0013/0014) — authoritative source = `feat/issue-20-b:docs/specs/issue-20-knowledge-promotion-loop.md` **PR C2 verbatim block** (branch `curator.prompt` artifacts have STALE 150/300 budgets — do not cargo-cult)
Outline: identity+idle · only writes `AGENTS.md`+`.agents/` · sources `~/.claude/worklog/retros/*.md` · routing ladder (backlog→AGENTS.md≤60→roles≤40→references→skills-on-2nd→upstream→ledger) · ledger `date|session-id|role|failure-class|verdict|summary` · lifecycle (empty-run→pass-through, knowledge branch, self-merge PR with metric line, move retros to processed/, notify specifier) · 9-check per-item algorithm (scope→recurrence→non-inferable→rule-not-phenomenon→dup/contradiction→global-fix-routing→trigger-load-fit→evidence-pull→sizing).
**Companion changes (locked spec, not on any branch):** specifier wait-on-curator (PR C4); `workflow.prompt` integrator→curator→specifier chain bullet (PR C5).

## Final `swarmforge.conf` window order (recover `feat/issue-20-c` for 8 windows + curator from `backup/six-pre-reset`)
```
window specifier   codex specifier      # was: codex master (0008 moves specifier off master)
window coder       codex coder
window ux-engineer codex ux-engineer     # 0007: after coder
window cleaner     codex cleaner
window architect   codex architect
window hardender   codex hardender
window QA          codex QA
window integrator  codex integrator      # 0008: after QA
window curator     codex curator         # 0013: last (only in backup/six-pre-reset)
```
Note: all roles still on `codex` → clear-first (0002) inert until roles move to `claude` or codex hooks built (open item). `default_branch` is per-feature specifier logic, not a conf field.
</content>
