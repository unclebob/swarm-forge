# Permanent fork of unclebob/swarm-forge with minimal diff policy

This repo (`gabadi/swarm-forge`) is a permanent fork of `unclebob/swarm-forge`. No changes are contributed back upstream. The policy is **minimal diff**: every divergence must be intentional and documented here. When rebasing against upstream, preserve permanent divergences and drop temporary ones only after verifying upstream has fixed them.

## Current divergences

### Permanent

**cmux multiplexer backend** — `main` branch, commits `863666c` and `655c457`

Files changed: `swarmforge/scripts/swarm-mux.sh` (new), `swarmforge/scripts/swarmforge.sh`, `swarmforge/scripts/swarm-cleanup.sh`, `swarmforge/scripts/swarm-stop.sh`, `README.md`. Deleted: `swarmforge/scripts/terminal-adapters/cmux.sh`.

Upstream only supports tmux. This environment runs inside cmux, where tmux behaves unreliably. The backend adds a pluggable multiplexer abstraction (`swarm-mux.sh`) that auto-detects cmux via `CMUX_*` env vars and runs each role as a native cmux workspace grouped under `SwarmForge · <project>`. When rebasing, always reapply these two commits on top of the updated upstream `main`.

---

**Self-referencing fork URL** — runnable branches (`four-pack`, `six-pack`), commit `ded6019`

The `./swarm` wrapper downloads shared scripts from `gabadi/swarm-forge`, not `unclebob/swarm-forge`. This must always point to this fork so projects get scripts that include the cmux backend. When rebasing runnable branches, reapply this URL change on top.

**Prompt bundle inlining at launch** — `main` branch, commit `08e7f25`

Files changed: `swarmforge/scripts/swarmforge.sh` — added `resolve_prompt_bundle`; rewrote `write_agent_instruction_file`.

At launch, the full prompt bundle for each role is resolved via BFS from `swarmforge/constitution.prompt` and `swarmforge/roles/<role>.prompt` (grepping for `swarmforge/*.prompt` references, deduped in discovery order) and written as a pre-resolved XML envelope (`<swarmforge_agent_context role="...">`, one `<file path="...">` block per file) to `.swarmforge/prompts/<role>.md`. The preamble tells agents not to re-read prompt files. For `claude`: delivered via `--append-system-prompt-file`. For codex/grok/others: delivered as the initial message. When rebasing, reapply these changes on top of upstream `main`.

---

**Auto-compaction on role worktrees** — `main` branch, commit `08e7f25`

Files changed: `swarmforge/scripts/swarmforge.sh` — added `write_worktree_permissions`; called from `prepare_worktrees`.

Each non-master role worktree gets `autoCompactEnabled: true`, `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE: "88"`, and `CLAUDE_CODE_AUTO_COMPACT_WINDOW: "200000"` merged into `.claude/settings.local.json` at swarm launch via `python3`. Idempotent. When rebasing, reapply this change on top of upstream `main`.

---

**Notify harness (partial — `main` only)** — `main` branch, commit `08e7f25`

Files changed: `swarmforge/scripts/swarmforge.sh` — added `write_deliver_script`, `write_stop_hook`; rewrote `write_notify_script`; extended `sessions.tsv` with worktree path; updated worktree wrappers to export `SWARMFORGE_SENDER_WORKTREE`.

The prompt-driven queue is replaced with a shell harness. `notify-agent.sh` (generated at launch) validates the sender worktree, reads git HEAD, writes `sent`+`executed` to the sender's `logbook.json`, checks the receiver's logbook for last terminal state (`executing`/`executed`), and either runs the delivery sequence immediately or appends a `pending` entry. The shared `swarmforge-deliver.sh` (also generated) runs the 7-step delivery: write `executing` → git reset → `/clear` → sleep 1s → `/rename` → bundle → message. Each role gets a per-role stop hook (baked-in path vars) that reads its own logbook and delivers the first `pending` entry after the last `executing` on every agent stop. Role prompt cleanup (`pending-messages/` removal, logbook write instruction removal) is still pending on `four-pack` and `six-pack` — see [idea-A](../ideas/idea-A-notify-harness.md). When rebasing, reapply these changes on top of upstream `main`.

---

### Temporary (drop once upstream fixes)

**logbook.json contradiction** — `four-pack` commit `6770ae4`, `six-pack` commit `d8b2e27`

File changed: `swarmforge/constitution/workflow.prompt` on each branch. Upstream's `workflow.prompt` told agents to commit `logbook.json`, but the file is gitignored — a direct contradiction. Fix: changed "tracked" to "local untracked" and removed the commit instruction. When rebasing, first check whether upstream has resolved this contradiction. If upstream has fixed it, drop these commits. If not, reapply them on both runnable branches.

## Proposed divergences

Ideas under consideration. Not yet designed or implemented. Each has a detailed spec in `docs/ideas/`. When each is decided: move it to **Current divergences** with files, classification, and rebase instruction — or strike it as rejected.

| Idea | Summary | Spec | Open questions |
|------|---------|------|----------------|
| A | Notify harness — shell queue, `/clear` + bundle re-inject, Stop hook idle signal, commit hash in trailer | [idea-A](../ideas/idea-A-notify-harness.md) | **Partially implemented** — `swarmforge.sh` on `main` done (commit `08e7f25`); role prompt cleanup pending on `four-pack`/`six-pack` |
| C | Integrator role — owns PR + CI + merge; specifier moves to own worktree | [idea-C](../ideas/idea-C-integrator-role.md) | **Decision** — design settled; see § "Design decisions: Idea C" below |
| D | Role idle gates — no handoff = no action, remove startup install directives | [idea-D](../ideas/idea-D-idle-gates.md) | **Decision** — design settled; see § "Design decisions: Idea D" below |
| E | Back-routing defects — always route to coder with failing step + repro | [idea-E](../ideas/idea-E-back-routing-defects.md) | **Decision** — design settled; see § "Design decisions: Idea E" below |
| G | Per-technology engineering file — selected at install time | [idea-G](../ideas/idea-G-per-tech-engineering-file.md) | **Rejected** — adding a language is 2-3 lines in the shared table; template machinery is not justified |
| H | swarm-cleanup --all mode | [idea-H](../ideas/idea-H-cleanup-all-mode.md) | **Rejected** — one-liner the operator can run manually; cmux UI covers the primary use case |
| I | swarmforge/ write deny on role worktrees | [idea-I](../ideas/idea-I-swarmforge-write-deny.md) | **Rejected** — deferred; revisit if prompt drift becomes a real observed problem |
| J | Session retro — `entire` auto-collects traces, `agent-retro` runs per turn | [idea-J](../ideas/idea-J-session-retro-entire.md) | **Decision** — design settled; see § "Design decisions: Idea J" below |
| K | Setup / preflight — `entire enable` + `entire agent add` per backend, automatic at first `./swarm` | [idea-K](../ideas/idea-K-setup-preflight.md) | **Decision** — design settled; see § "Design decisions: Idea K" below |
| L | Gherkin header sections — 7 mandatory sections per feature file (rubric + format) | [idea-L](../ideas/idea-L-gherkin-header-sections.md) | **Decision** — design settled; see § "Design decisions: Idea L" below |
| M | UX Intent in the pipeline — specifier authors UX Intent, coder reads it, UX Engineer role (six-pack only) | [idea-M](../ideas/idea-M-ux-intent-pipeline.md) | **Decision** — design settled; see § "Design decisions: Idea M" below |

**Rejected**: D12–D15 (engineering prompt tweaks — test-type separation, property-test close-out, full-mutation rule, Gherkin mutation command inline) — too much prompt drift from upstream. D24 (role prompt restructure into Standing rules + numbered Lifecycle) — same reason. G (per-technology engineering file — template system not justified; adding a language is 2-3 lines in the shared table). H (swarm-cleanup --all — one-liner the operator runs manually; cmux UI covers the primary use case). I (swarmforge write-deny — deferred; revisit if prompt drift becomes an observed problem).

### Design decisions: Idea M

Design settled. See `CONTEXT.md` for domain vocabulary (UX Intent, UX Engineer).

**"UX Reviewer" reframed as "UX Engineer".** The pipeline pattern requires every role to add a durable artifact and fix problems in place. A pure reviewer role (report only, no fixes) is inconsistent with this pattern and confirmed by pipeline research as an anti-pattern. The UX Engineer fixes mismatches and produces golden file snapshots and rendering invariants.

**UX Intent lives in the feature file.** The feature file is the canonical pipeline artifact read by all roles with no ignore-rule friction. Adding `## UX Intent` as a comment section follows the existing template style (Idea L). The Gherkin parser ignores non-Gherkin prose. No role prompt needs an ignore-rule exception.

**Skip condition is self-managed.** If the feature file has no `## UX Intent` section, the UX Engineer notifies QA immediately without changes. No harness routing logic needed — same pattern as "if no changes, do not hand off."

**Tooling delegated to the constitution.** The role prompt defines the class of testing (golden file snapshots of `View()` output, rendering invariants). The constitution names the specific tool. This keeps the role prompt portable across TUI frameworks.

**Back-routing to coder is permitted with guards.** The pipeline already has a circular routing pattern (integrator → owning role on CI failure). The UX Engineer uses the same pattern for mismatches too deep to fix without changing model state. Guards required: (1) specific actionable message (what UX Intent says, what the implementation does, what must change), (2) full pipeline re-run — coder → cleaner → architect → hardener → UX Engineer, (3) depth cap N=3 tracked via routing count in the handoff message trail. After cap exhaustion: stop and ask the user.

**`agent-retro before idle` applies.** UX Engineer is a new six-pack role; Idea J's rule applies — all six-pack roles get `agent-retro before idle`.

**`hardender` typo fixed in the same change.** Idea M edits `swarmforge.conf` to add the UX Engineer window; the typo is corrected in the same edit.

**DESIGN.md as project-level design contract.** The UX Engineer reads both UX Intent (per-feature, from the feature file) and the nearest DESIGN.md (project-level persistent design contract). This separates two concerns: "what should this feature look like" (UX Intent) vs "what does this product look like" (DESIGN.md). Without this separation, UX Intent authors must re-invent the project's visual language on every feature, producing visual drift. DESIGN.md is discovered via nearest-file resolution — walk up from the files being touched. In a monorepo each app may carry its own DESIGN.md.

**UX Engineer has fix authority over DESIGN.md violations.** The UX Engineer may fix rendering code that violates DESIGN.md even when the violation is absent from UX Intent. Authority stems from the user having approved DESIGN.md at creation time — DESIGN.md is already user-authorised design law. Alternatives rejected: (1) flag-only — adds user friction on every feature where a violation exists; (2) spec-expansion — UX Engineer adds missing UX Intent statements then fixes — creates spec bloat and violates specifier ownership. All fixes made are reported for auditability.

**Specifier scaffolds DESIGN.md on first UX feature.** If UX Intent is required and no DESIGN.md exists nearby, the specifier generates a DESIGN.md scaffold and presents it for user approval before sending the handoff. Rationale: DESIGN.md is a user-authorised document — the user must approve it before it becomes design law. The specifier generates the scaffold because it has the feature context; the user reviews and adjusts. Follows the existing approval-gate pattern (specifier never commits or notifies coder until the user explicitly approves).

**Universal aesthetic anti-patterns inlined in role prompt.** A subset of visual quality rules (AI-slop anti-patterns, typography hierarchy, color contrast requirements) is inlined directly in `ux-engineer.prompt`. These are universal — they apply regardless of what any project's DESIGN.md says. All other content from the reference skill (component architecture, state management, accessibility implementation) belongs to the coder and QA, not the UX Engineer.

**Pipeline after Idea M (six-pack):**
```
specifier → coder → cleaner → architect → hardener → UX Engineer → QA → integrator
```

**Files changed (six-pack only):**
- `six-pack`: `swarmforge/templates/feature.feature` — add `## UX Intent` section
- `six-pack`: `swarmforge/roles/specifier.prompt` — add UX Intent authoring step before phase 1; add DESIGN.md scaffolding step on first UX feature
- `six-pack`: `swarmforge/roles/coder.prompt` — add UX Intent reading instruction
- `six-pack`: `swarmforge/roles/hardener.prompt` — add rendering property tests instruction; change final notification from QA to ux-engineer
- `six-pack`: `swarmforge/roles/ux-engineer.prompt` (new) — include DESIGN.md reading, fix authority over DESIGN.md violations, inlined aesthetic anti-patterns
- `six-pack`: `swarmforge/swarmforge.conf` — fix `hardender` typo; add ux-engineer window between hardener and QA

---

### Design decisions: Idea L

Design settled. No new domain vocabulary.

**Template adopted as-is from melech-mini-apps.** The 7 sections (TRACKING, CONTRACT, CONSTRAINTS, SEQUENCING, NFR, SIDE EFFECTS, SCOPE) are proven in practice — no modification.

**One specifier prompt change per branch.** Phase 1 gains a single sentence: "Start from `swarmforge/templates/feature.feature`; complete all seven header sections before writing scenarios." Applies to both four-pack and six-pack specifier prompts.

**Files changed:**
- `four-pack` + `six-pack`: `swarmforge/templates/feature.feature` (new)
- `four-pack` + `six-pack`: `swarmforge/roles/specifier.prompt` — phase 1 updated

---

### Design decisions: Idea K

Design settled. No new domain vocabulary.

**No `--agent` in `entire enable`.** `entire enable --no-github --telemetry=false` enables entire non-interactively without installing hooks for a hardcoded agent. Correct agent hooks are installed in the next step — derived from `swarmforge.conf`, not assumed.

**Backends derived from `swarmforge.conf`.** Column 3 of each `window` line is the agent backend. Unique values are extracted and `entire agent add <backend>` is called for each. No user input required.

**Sentinel gates re-runs.** `.swarmforge/setup-complete` is written after successful setup. If it exists, preflight is skipped. To force re-run: delete the file. No `./swarm setup` subcommand — operator does it manually.

**Warn and continue if `entire` not installed.** Setup failure does not block the swarm. Retros run via `agent-retro` but without trace backing.

**Files changed:**
- `four-pack` + `six-pack`: `./swarm` — preflight block before role launch

---

### Design decisions: Idea J

Design settled. No new domain vocabulary.

**`entire` as trace source, write to file.** `agent-retro` Step 1 uses `entire session current` to get the active session ID, then `entire session info <id> --transcript > /tmp/retro-session.jsonl` to write the transcript to a temp file. `extract.py` runs against that file. Raw JSONL is 1MB+ per session — streaming transcript bytes inline into context is not acceptable.

**Fallback to `~/.claude/projects/`.** If `entire session current` returns no session (entire not installed or not tracking), the existing Claude Code path (`~/.claude/projects/`) is used. This preserves backward compatibility for Claude Code agents.

**Codex JSONL schema compatibility: risk accepted.** `extract.py` is tuned to Claude Code's JSONL schema. Codex JSONL uses the same container format but different field names — extraction may be partial. Risk accepted; will surface during implementation.

**`agent-retro before idle` added to all role prompts.** Four-pack: specifier, coder, refactorer, architect. Six-pack: those four plus QA, cleaner, hardender.

**Files changed:**
- `agent-retro` skill `SKILL.md` (project-local, installed by operator) — Step 1: `entire`-backed extraction with fallback
- `four-pack` + `six-pack`: `swarmforge/roles/*.prompt` — `agent-retro before idle` as final lifecycle step

---

### Design decisions: Idea F

Design settled. No new domain vocabulary.

**Merge, never overwrite.** `write_worktree_permissions` reads the existing `.claude/settings.local.json` (or starts from `{}`), unions in the compaction settings, and writes back via `python3`. Idempotent.

**Values fixed.** `autoCompactEnabled: true`, `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE: "88"`, `CLAUDE_CODE_AUTO_COMPACT_WINDOW: "200000"`. No per-role overrides.

**Files changed:**
- `main`: `swarmforge/scripts/swarmforge.sh` — add `write_worktree_permissions` function; call it from `prepare_worktrees`

---

### Design decisions: Idea E

Design settled. No new domain vocabulary.

**All mid-pipeline defects route to the coder.** Any role that discovers a defect it does not own routes it directly to the coder — no ownership table, no hop-by-hop chaining. The coder is the implementation owner; defects in tests, structure, or analysis ultimately trace back to the implementation and are for the coder to resolve before re-entering the pipeline.

**One rule added to `constitution/workflow.prompt`:** "When you discover a defect you do not own, route it to the coder. Include: the failing step, the raw error output, your diagnosis, and a repro recipe. Autofixable issues (formatting, linting) are excepted — fix those in place."

**Complementary to Idea C integrator routing.** The integrator routes CI failures at the landing stage (Idea C). This constitution rule covers mid-pipeline defects discovered before landing. Both rules coexist.

**Files changed:**
- `four-pack` + `six-pack`: `swarmforge/constitution/workflow.prompt` — one additive rule, no upstream content removed

---

### Design decisions: Idea D

Design settled. See `CONTEXT.md` for domain vocabulary (Handoff, Idle gate).

**Idle gate wording.** Every role prompt on both branches gets one line added: "Wait for a handoff. Do not act without one." No enumeration of prohibited actions — the single rule is sufficient.

**Startup install directives removed.** All "At startup, install/build X" lines are dropped from role prompts. Rationale: with Idea A's delivery sequence, the full bundle (including startup directives) is re-sent on every handoff — startup actions would fire on every task, not just cold launch. Tool installation belongs in project setup (Idea K).

**Applies to all roles, both branches.** four-pack: coder, architect, refactorer, specifier. six-pack: coder, architect, QA, cleaner, hardender, specifier.

**Files changed:**
- `four-pack`: `swarmforge/roles/coder.prompt`, `architect.prompt`, `refactorer.prompt`, `specifier.prompt`
- `six-pack`: `swarmforge/roles/coder.prompt`, `architect.prompt`, `QA.prompt`, `cleaner.prompt`, `hardender.prompt`, `specifier.prompt`

---

### Design decisions: Idea C

Design settled. See `CONTEXT.md` for domain vocabulary (Landing, Routing cycle, Depth cap). Key decisions:

**Integrator is the terminal role; owns landing only.** No code changes. The only path to trunk is through the integrator via one PR per feature.

**Per-feature branch.** The integrator creates `feat/<initiative>` from the handoff commit. `swarmforge-integrator` is never the PR head. After PR creation, the integrator checks back out to `swarmforge-integrator` so the feature branch can be deleted on merge.

**Fix-in-place: autofixable only.** Lint/format failures may be fixed in place on the PR branch. Line-count heuristic removed — any real code change routes back to the owning role regardless of size.

**Direct CI routing.** The integrator routes CI failures directly to the owning role, bypassing the hop-by-hop constitution rule (Idea E). Failing tests → coder; failing CRAP/DRY → cleanliness role (refactorer in four-pack, cleaner in six-pack); failing arch-check → architect. Idea E's hop-by-hop rule covers mid-pipeline defects; at landing the CI output identifies the owner directly.

**Post-merge gate.** After merge: watch post-merge `main` CI with `gh run watch`. If the project's constitution/engineering.prompt defines a full verification suite command, run it on green. Skip if none defined — the CI gate is the minimum.

**Specifier moves to own worktree.** Resets to `origin/main` at the start of each new task. Merge instruction removed from specifier prompt — integrator owns all merging.

**Terminal quality roles redirect to integrator.** Architect (four-pack) and QA (six-pack) notify integrator instead of specifier. The integrator notifies the specifier after the PR lands.

**Depth cap N=3, tracked via PR comment history.** After 3 failed routing cycles the integrator leaves a FAILED comment and goes idle. Cycle count is derived by counting the integrator's own failure comments on the PR — no new state mechanism needed.

**Files changed:**
- `four-pack` + `six-pack`: `swarmforge/swarmforge.conf` — add integrator window; change specifier from `master` to `specifier` worktree
- `four-pack` + `six-pack`: `swarmforge/roles/integrator.prompt` (new)
- `four-pack` + `six-pack`: `swarmforge/roles/specifier.prompt` — add reset-to-origin/main step; remove merge instruction
- `four-pack`: `swarmforge/roles/architect.prompt` — notify integrator instead of specifier
- `six-pack`: `swarmforge/roles/QA.prompt` — notify integrator instead of specifier

---

### Design decisions: Idea B

Design settled. See `CONTEXT.md` for domain vocabulary (Prompt bundle, Bundle cache). Key decisions:

**XML envelope adopted.** `write_agent_instruction_file` wraps the resolved bundle in `<swarmforge_agent_context role="...">` with an `<instructions>` preamble and one `<file path="...">` block per file. The preamble explicitly tells the agent the content is pre-resolved — it must not open the prompt files itself.

**BFS resolver with regex.** `resolve_prompt_bundle` walks each file grepping for `swarmforge/[A-Za-z0-9_./-]+\.prompt` references, BFS with dedup. Constitution bundle resolved first, role bundle merged after (deduped against constitution). This keeps the resolver in sync automatically if constitution sub-files are added or removed.

**Agent-agnostic bundle, delivery channel differs.** `write_agent_instruction_file` produces the same XML regardless of agent type. For `claude`: delivered via `--append-system-prompt-file`. For codex/grok/others: delivered via `$(cat bundle)` as the initial message. `/clear` survival (system prompt) is a claude-only benefit; other agents rely on Idea A's delivery sequence for re-injection.

**Files changed:**
- `main`: `swarmforge/scripts/swarmforge.sh` — add `resolve_prompt_bundle`; rewrite `write_agent_instruction_file`

---

### Design decisions: Idea A

Design settled. See `CONTEXT.md` for domain vocabulary. Key decisions recorded here:

**Harness owns all logbook writes.** The agent never writes to `logbook.json`. The harness writes all four statuses.

**One logbook per role.** Contains both outbound state (what this role sent) and inbound state (tasks queued for this role).

**Logbook statuses:**
- `pending` — appended by `notify-agent.sh` into the TARGET role's logbook when that role is busy; contains message content. Multiple entries allowed; delivered in order.
- `executing` — written by harness as step 0 of the delivery sequence (before `/clear`).
- `sent` — written by `notify-agent.sh` to the CALLING role's own logbook; audit trail (target, message, commit hash).
- `executed` — written by `notify-agent.sh` to the CALLING role's own logbook immediately after `sent`; the act of calling `notify-agent.sh` is the task-complete signal. The Stop hook reads this as the idle signal.

**`notify-agent.sh` never rejects.** If receiver is busy, it always appends a `pending` entry. No error path for "already queued."

**Delivery sequence** (run by both `notify-agent.sh` on immediate delivery and the Stop hook on queued delivery):
1. Write `executing` to receiver's logbook
2. `git reset --hard <hash>` in receiver's worktree (hash from `[handoff]` trailer)
3. Send `/clear` to receiver's terminal
4. Sleep 1s
5. Send `/rename SwarmForge <display-name>` (restores name wiped by `/clear`; baked in at launch)
6. Send cached bundle (`.swarmforge/prompts/<role>.md`)
7. Send task message

**Stop hook logic:** Read own logbook. Find last terminal status (`executing` or `executed`). If `executed`: find first `pending` entry that appears after the last `executing` entry — deliver it via the delivery sequence. Otherwise do nothing.

**Cross-logbook scanning removed.** The Stop hook only reads its own logbook. No scanning of sender logbooks.

## Rebase procedure for agents

1. Add upstream as a remote: `git remote add upstream https://github.com/unclebob/swarm-forge.git`
2. Fetch: `git fetch upstream --all`
3. For each branch (`main`, `four-pack`, `six-pack`): rebase onto the upstream branch
4. After rebase, verify permanent divergences survived (check `swarm-mux.sh` exists, fork URL is correct)
5. For temporary divergences: check if upstream now ships the fix. If yes, do not reapply. If no, cherry-pick the fix commit onto both runnable branches.
6. Remove temp remote: `git remote remove upstream`
