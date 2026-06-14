# Fork change manifest

Compact, permanent record of **every divergence to apply on top of a pristine `upstream`**, one line per change. Rationale lives in the ADRs (`docs/adr/`) — this file is *where + what + source*, not *why*. Use it to (re)apply the fork after any upstream sync.

## Sync policy (ADR 0001)

- `main`, `six-pack`, `four-pack` are kept **identical to `upstream/<branch>`** and advanced by **merge** (`git merge upstream/<branch>`), never rebase. `rerere` replays conflict resolutions.
- **four-pack is frozen (decision 2026-06-14): no fork divergences are applied to it.** Only `main` and `six-pack` carry changes. (Open: whether four-pack is still resynced to upstream to honor "keep == upstream", or left as-is — see below.)
- Every item below is **additive** (new file or appended rule) wherever possible; a non-additive edit to an upstream line is marked **[edit]** and is a conscious, documented conflict point.
- **Delivery routing:** `main` ← scripts + skills + docs/ADRs · `six-pack` ← role prompts, constitution articles, templates, manifest, `swarmforge.conf`.
- Never push `main` without explicit request; **never** push `upstream` (`gh` defaults to upstream → always `--repo gabadi/swarm-forge`).

## Source legend

- **ADR** — `docs/adr/NNNN-*.md` (decision + rationale + `## Pending implementation`).
- **B6** — `backup/six-pre-reset` (real pre-reset six-pack artifacts: prompts, manifest, template, conf). Re-merge onto *current* prompts; do **not** copy whole files (they predate current upstream; some carry behavior the ADRs removed).
- **I20A** — `feat/issue-20-a-retro-skill-upgrade` (`swarmforge/skills/agent-retro/`, `AGENTS.md`).
- **I20B** — `feat/issue-20-b-bundle-knowledge-injection:docs/specs/issue-20-knowledge-promotion-loop.md` (locked curator-loop spec, PRs A→B→C→D; **spec wins** over issue #20; budgets AGENTS.md ≤60 / role files ≤40).

## Per-row recovery docs (exact recover-from `branch:path` + delta + STRIP per item)

- `docs/migrations/main-script-layer.md` — all Section A + Section C `swarmforge.sh`/scripts rows. **⚠ Idea B + cmux + M3 + executing-fields are one entangled ~400-line restructure — gating decision: keep the full cmux delivery model or rebuild lean on upstream's harness.**
- `docs/migrations/six-pack-role-prompts.md` — all Section B/C role-prompt rows + the 3 new roles + final conf window order + the STRIP table (backup content ADRs reversed).
- `docs/migrations/0003-setup-skill-sources.md` — setup skill design recovery (net-new, no code).

---

## A. `main` — scripts / skills / docs

Script path: `swarmforge/scripts/swarmforge.sh`. Skills path: `swarmforge/skills/`.

| ADR | Change (one line) | Where | Source |
|-----|-------------------|-------|--------|
| 0006 | In `prepare_worktrees` (`git worktree add`, ~L331) add `git sparse-checkout` excluding the pinned QA-suite path for **every worktree except specifier(`master`) and QA**; verify the path survives each role's handoff commit. | `swarmforge.sh` `prepare_worktrees` | ADR 0006 · **NET-NEW (no impl)** |
| 0012 | `parse_config` (~L182, today rejects ≠4 fields) → accept **≥4 fields**, parse `key=value` tail into a per-role map; `launch_role` (~L414) → append mapped flags per backend. **[edit]** | `swarmforge.sh` | ADR 0012 · recover `backup/main-pre-reset` · **advisor = `advisorModel` in settings.local.json, not `--advisor`** ✅ |
| 0014 | `write_agent_instruction_file` (~L389) → append project-root `AGENTS.md` + `.agents/roles/<role>.md` when present, plus a preamble sentence; missing files silently skipped. | `swarmforge.sh` | ADR 0014 + I20B(PR-B) · **needs Idea B first** |
| 0013 | Upgrade `agent-retro` skill: per-action **scope tag** (`project\|swarmforge\|skill\|ephemeral`), **capture-first** (no pre-filter), **autonomous** mode marking actions `pending-curation` without a human prompt. | `swarmforge/skills/agent-retro/` | ADR 0013 + I20A + I20B(PR-A) |
| 0003 | New **`setup-swarm` skill** (stack detection; writes tooling/permissions/skill-pins/session-tracking; emits the **swarm-ready marker** `.swarmforge/setup-complete`); **setup-first** — operator runs `/setup-swarm` as step one, `./swarm` only **guards** on the marker and refuses if unset (never auto-runs setup). Absorbs Idea O scaffold. *Impl details open: marker format, stack detection (no backup artifact).* | `swarmforge/skills/setup-swarm/` (new) | ADR 0003 |

---

## B. `six-pack` — prompts / constitution / templates / conf

Roles: `swarmforge/roles/*.prompt` · constitution: `swarmforge/constitution/articles/*.prompt` · `swarmforge/swarmforge.conf`.

| ADR | Change (one line) | Where | Source |
|-----|-------------------|-------|--------|
| 0002/0003 | Remove the `At startup, install/make-ready …` directive(s): `coder`:9, `QA`:7, `cleaner`:19, `hardender`:8–9. **[edit]** | `roles/*.prompt` | ADR 0002, 0003 |
| 0002 | Add idle-gate rule to each role prompt: "Wait for a handoff. Do not act without one." | `roles/*.prompt` | ADR 0002 |
| 0009 | Add `swarmforge/templates/feature.feature` — **8-section** spec header (TRACKING/CONTRACT/CONSTRAINTS/SEQUENCING/NFR/SIDE EFFECTS/SCOPE + UX INTENT). | `templates/feature.feature` (new) | ADR 0009 + B6 |
| 0009 | Specifier phase 1 starts from the template, addresses **all** sections before scenarios; fix stale count "seven" → **"eight"/"all"**. | `roles/specifier.prompt` | ADR 0009 + B6 |
| 0011 | Add `swarmforge/dependency-manifest.prompt` (3 tier defs inline + Rules section, body `(none)`); auto-resolved by the bundle resolver. | `dependency-manifest.prompt` (new) | ADR 0011 · recover `feat/baseline-scenarios-six` (**obs-harness-six over-deleted the Rules section**) |
| 0011 | Specifier reads the manifest before scenarios; on an undeclared external system → stop, propose name/tier/impl/gaps, wait for approval. | `roles/specifier.prompt` | ADR 0011 + B6 |
| 0010 | Add **surface-tool table** + context-driven acquisition rule (tmux/PTY · Playwright · HTTP client · ingress event-injection) to `engineering.prompt`. | `constitution/articles/engineering.prompt` | ADR 0010 + B6 |
| 0010 | Require a per-surface **baseline scenario** committed with every feature's flow scenarios (idle stability / no console errors / no-op event = no state change). | spec-header + role prompts | ADR 0010 |
| 0015 | Add platform-feasibility **stop rule** to `workflow.prompt` (spec-vs-platform conflict → stop & report; a workaround comment is a defect). | `constitution/articles/workflow.prompt` | ADR 0015 |
| 0005 | Rewrite QA to a **refute** posture (assume build fails spec & tests are weak; attack within the spec; conversion fidelity); replace "Fix bugs found by the QA suite…" (`QA`:14) — local fix in place, structural routes back. **[edit]** | `roles/QA.prompt` | ADR 0005 + B6 |
| 0010 | QA: replace "through the user interface only" (`QA`:13) with "**through the declared surface harness**"; add **every Expected bullet → a harness assertion or `NOT AUTOMATED — <reason>`**; QA re-executes committed `observation-harness/`, routes back if a user-facing surface has none. **[edit]** | `roles/QA.prompt` | ADR 0010 + B6 |
| 0004 | Add back-routing rule to role prompts: structural finding routes to its origin stage; local stays with finder; single-finding cap (back **once**) + feature cap **N=3** via routing count in the handoff trail (ux-engineer & integrator carry N=3). | `roles/*.prompt` | ADR 0004 |
| 0007 | Add **UX Engineer** role after coder (runs product, fixes rendering vs UX Intent, universal visual-quality bar incl. WCAG 4.5:1/3:1, writes `observation-harness/` + snapshots + rendering invariants; routes back per 0004 N=3); add conf window after coder. **Strip** DESIGN.md scaffold/walk-up from B6 draft. | `roles/ux-engineer.prompt` (new) + `swarmforge.conf` | ADR 0007 + B6 |
| 0007 | Coder reads UX Intent; specifier authors the UX INTENT section. | `roles/coder.prompt`, `roles/specifier.prompt` | ADR 0007 |
| 0008 | Add terminal **integrator** role (PR + green CI, post-merge gate, one PR/feature, autofix lint only, **hands off to curator**); add conf window. | `roles/integrator.prompt` (new) + `swarmforge.conf` | ADR 0008 + B6 |
| 0008 | Specifier **stops merging**: drop merge step (specifier:36), move specifier off `master` to its own worktree, reset to default branch per feature. **[edit]** | `roles/specifier.prompt` + `swarmforge.conf` | ADR 0008 |
| 0013 | Add terminal **curator** role (promotes retros → `.agents/`+`AGENTS.md` via one self-merging PR, then releases specifier; empty run = pass-through); rewire **integrator→curator→specifier**; conf curator window last; document chain in `workflow.prompt`. | `roles/curator.prompt` (new) + `swarmforge.conf` + `workflow.prompt` | ADR 0013 + B6 + I20B(PR-C) |
| — | **hardener** rendering-invariant property tests for pure rendering fns (state→string) — **unmanifested divergence found in audit**; consistent w/ 0007/0010. | `roles/hardender.prompt:18` | recover `backup/six-pre-reset` |
| 0016 | `cleaner` also scans **boundary files** at ~15–20-site threshold (vs 100 for testable source), extracts logic to a testable module; add the "stripped-view = untested" anti-pattern. | `roles/cleaner.prompt` | ADR 0016 + B6 |

---

## C. Uncaptured implemented divergences — NO ADR (recover from backup, else lost on rebase)

The 16 ADRs document the **behavioral/prompt** layer but not the **`main`-side script infrastructure**. The items below are real, implemented divergences with **no ADR**, living only in the monolith ADR (`backup/main-pre-reset:docs/adr/0001-fork-divergence.md`, "§Idea X") + the backup/feat branches. **Each verified as still a divergence vs current `upstream/main` (2026-06-14).** They are prerequisites/peers of Section A — a clean rebase that follows only the ADRs would drop them. **Decide per item: write an ADR, or carry as a manifest row.**

| Idea | Divergence (one line) | Verified vs upstream | Source artifact | ADR? |
|------|----------------------|----------------------|-----------------|------|
| B | **Prompt-bundle inlining** — `write_agent_instruction_file` emits XML envelope `<swarmforge_agent_context>` + `resolve_prompt_bundle` (BFS over `*.prompt` refs, dedup). **KEEP (decision 2026-06-14).** Must be **disentangled from cmux**: port the resolver + envelope onto upstream's tmux harness and wire the bundle into upstream's delivery (NOT cmux's `write_deliver_script`). Prerequisite for M3/0014. | upstream has the naive read-recursively form only | `backup/main-pre-reset:swarmforge/scripts/swarmforge.sh` (`resolve_prompt_bundle`, `write_agent_instruction_file`); re-base, don't lift | **0017** |
| F | **Auto-compaction on role worktrees** — `write_worktree_permissions` merges into `.claude/settings.local.json`: `autoCompactEnabled:true`, `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE:"88"`, `CLAUDE_CODE_AUTO_COMPACT_WINDOW:"200000"`. | absent upstream | `backup/main-pre-reset` (commit 08e7f25); `mono §Idea F:207` | **0020** |
| J | **Session-retro plumbing** — `agent-retro` uses `entire session current`→`session info --transcript >/tmp`; fallback `~/.claude/projects/`; Codex-schema risk accepted; `agent-retro before idle` line in every role prompt. | absent upstream | `feat/issue-20-a…:swarmforge/skills/agent-retro/`; `mono §Idea J:189` | extend **0013** |
| N | **`./swarm upgrade`** — refresh scripts(main)+prompts(source branch)+skills; `install-pins.conf` SHA pinning; `.swarmforge/source-branch` tracking; auto-install skills on first launch via `.swarmforge/skills-installed`. | absent upstream | `mono §Idea N:88` | **0018** |
| O | **Install scaffold** — `.gitignore` gen (`logbook.json`,`tmp/`,`.swarmforge/`); default-branch probe→`swarmforge.conf`; permission allow-rules. **Overlaps setup-swarm skill (0003).** | absent upstream | `mono §Idea O:326` | folds into **0003** |
| — | **Autonomous permission mode** — `--permission-mode auto` (not `acceptEdits`) in `launch_role`. | upstream = `acceptEdits` (L433/442) | `backup/main-pre-reset` (commit 1097233) | **0019** |
| — | **cmux multiplexer backend** — `swarm-mux.sh`. **DROP — not wanted in the new fork (decision 2026-06-14).** Stay on upstream's tmux harness. Dropping this is what un-tangles Idea B / executing-fields / M3. | no mux file upstream | n/a — not reapplied | **DROP** |
| — | **`executing` logbook entry carries `{message,hash,sender}`** for session-restart recovery (ADR 0002 names only the idle/busy marker). | absent upstream | `feat/main-executing-context-fields:swarmforge/scripts/swarmforge.sh` | extend **0002** |
| — | **retro-triage skill** — `.claude/skills/retro-triage/` (~219 lines), diagnosis-first batch retro. **KEEP — restore (decision 2026-06-14).** Byte-identical on all branches; recover as-is. | absent upstream | `feat/issue-20-a…:.claude/skills/retro-triage/SKILL.md` | **0021** |
| — | **Self-referencing fork URL** — `./swarm` self-fetch points at the fork. | upstream points at unclebob | `backup/main-pre-reset` (commit ded6019) | row-only |
| — | **Richer `CONTEXT.md` glossary** — Task / Logbook / Prompt bundle / Bundle cache / Landing / Depth cap / full logbook-status spec; leaner than the backup version. | n/a (docs) | `backup/main-pre-reset:CONTEXT.md` | doc-merge |

Not-lost / already consistent (no action): curator budget **60/40** (ADR 0013 + I20B spec win over backup prompts' stale 150/300); DESIGN.md **not scaffolded** (ADR 0007 wins over `mono §Idea M`); back-routing **to owning stage** (ADR 0004 wins over `mono §Idea E` "always to coder"). Genuinely rejected (no recover): ideas **G, H, I**.
Also unimplemented draft, not a divergence: `backup/main-pre-reset:docs/proposals/2026-06-11-factory-line-refactor.md` (architecture audit; status draft).

---

## Cross-cutting invariants (do not break while applying)

- **observation-harness/** is shared: ux-engineer writes (0007), doctrine (0010), QA re-executes (0010), hardener honors rendering invariants — keep consistent.
- **Back-route N=3** (0004) referenced by ux-engineer & integrator — keep the routing-count-in-handoff mechanic.
- **Refuting QA (0005)** is *new*; the B6 QA draft already has the 0010 surface-harness wording — **merge both** when writing QA.prompt.
- **DESIGN.md** is referenced-from-feature-file only (0007) — when porting B6 specifier/ux-engineer, delete scaffold-on-absence and nearest-file walk-up.
- **Curator PRs land in order** A→B→C→D (I20B); everything else is independently landable.

## Still open (decisions / unknowns)

*(resolved 2026-06-14, grilling session — what's-missing pass)*

0. **Section C scope** — RESOLVED. All Section-C items kept (cmux already dropped). ADR assignments: B→**0017**, N→**0018**, auto-permission→**0019**, F→**0020**, retro-triage→**0021**; J→extend **0013**, executing-fields→extend **0002**, O→folds into **0003**; self-url→row-only; CONTEXT glossary→doc-merge. **Idea B remains a hard prerequisite for M3/ADR 0014.**
1. **ADR 0003 setup-swarm skill** — idea-K conflict RESOLVED: setup is **setup-first** (operator runs `/setup-swarm` as step one); `./swarm` **guards** on the `.swarmforge/setup-complete` marker and refuses if absent — it never auto-runs setup. Skill **renamed `setup` → `setup-swarm`**. Idea O folds in. *Remaining impl details (not blockers): marker content format, stack-detection mechanism, per-language tool selection — captured in `docs/migrations/0003-setup-skill-sources.md`.*
2. **ADR 0002 clear-first on six-pack** — RESOLVED: the model column is **configuration** (governed by ADR 0012's per-role model), not an architectural decision. No codex-hook work is added. ADR 0002 stands as written — clear-first is claude-first; codex roles keep upstream delivery as a documented property.
3. *(resolved earlier)* cmux **DROPPED** (stay on upstream tmux harness); Idea-B bundle-inlining **KEPT** but disentangled — port `resolve_prompt_bundle` + XML envelope onto upstream's harness, re-base executing-fields/M3 on it. ADR 0012 `--advisor` resolved (`advisorModel` in `settings.local.json`).
4. **four-pack** — RESOLVED: kept as a **pure merge-mirror of `upstream/four-pack`** (no fork content ever) to honor ADR 0001's "all branches == upstream"; resync via merge-only.
5. **PR shape for implementation** — DEFERRED to implementation time (does not affect the ADR set). Note the one-difference-per-ADR rule; likely grouped by layer + dependency (B → 0014/M3 → executing-fields ordered).

**Overriding constraint (all items):** keep the diff vs upstream as small as possible — translate to the minimal additive form, do not lift the pre-reset implementation. See `feedback-minimize-upstream-diff` memory.
</content>
