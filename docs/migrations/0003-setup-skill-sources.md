# Migration source list — ADR 0003 setup skill

Working source list to implement the **setup skill** (ADR 0003) without losing decisions already made in the pre-reset work. ADR 0003 decided *that* setup becomes a one-time skill; the *how* lives scattered across idea-K, the monolith ADR, ideas N/O, and the "At startup, install…" lines being removed. There is **no implemented setup skill in any branch** (confirmed) — this is design recovery, not code recovery.

Refs: `idea-K` = `origin/docs/ideas-backlog:docs/ideas/idea-K-setup-preflight.md` · `mono` = `backup/main-pre-reset:docs/adr/0001-fork-divergence.md` · `ADR` = `docs/adr/0003-setup-is-a-one-time-skill.md`.

## ✅ Resolved (2026-06-14): setup-first, guard-only; skill renamed `setup-swarm`

- **idea-K** (auto-run on first launch) is **superseded.** `./swarm` never runs setup; the auto-run + stale `backup/main-pre-reset:CLAUDE.md:12` line are dead.
- **ADR 0003 form wins:** setup is **setup-first** — the operator runs `/setup-swarm` as the project's *first* action. `./swarm` is the *second* action and only **guards**: if `.swarmforge/setup-complete` is absent it refuses and tells the operator to run `setup-swarm` first.
- **Skill renamed `setup` → `setup-swarm`** (operator-facing `/setup-swarm`). Glossary updated (`CONTEXT.md`: `setup-swarm`, `swarm-ready marker`). Skill path: `swarmforge/skills/setup-swarm/`.

## Decisions already made (cite before re-deciding)

- Setup is a **skill** (fork-owned file, zero upstream conflict), not a `swarmforge.sh` function — `ADR`.
- Run path installs **no project tooling**; `./swarm` still self-fetches scripts, does worktree/session plumbing, **and auto-installs the swarm's own `entire` skills (pin-aware `ensure_skills_installed`, owned by ADR 0018)**; stops if the project isn't set up — `ADR`. *(Decision 2026-06-14: launcher infra-bootstrap stays automatic; only project provisioning is gated by the setup-swarm marker. See `main-script-layer.md` Idea N row.)*
- Skill **reasons about the stack** (Go vs Java vs Clojure → which tools/gates) — that's the point of a skill over a script — `ADR`.
- `entire enable --no-github --telemetry=false` (no `--agent`; hooks added separately) — `idea-K`, `mono §Idea K`.
- Backends derived from `swarmforge.conf` col 3 → `entire agent add <backend>` per unique value; no user input — `idea-K`, `mono §K:178`.
- Warn-and-continue if `entire` absent (setup never blocks the swarm) — `idea-K`, `mono §K:182`.
- No `./swarm setup` subcommand; force re-run = operator deletes the marker — `idea-K`, `mono §K:180`.
- Idea G (per-tech engineering template system) **rejected** — adding a language is 2–3 lines in the shared table — `idea-G`, `mono:69`.

## What the setup skill must take over (from the removed "At startup, install…" lines)

| Category | Detail | Removed-line source |
|----------|--------|---------------------|
| Mutation/CRAP/DRY tools | language mutation + CRAP + DRY, from `engineering.prompt` | `upstream/six-pack:roles/cleaner.prompt:19`, `hardender.prompt:8`, `QA.prompt:7` |
| Acceptance Pipeline (APS) | ensure pipeline in place; build `gherkin-parser` + `gherkin-mutator` from `github.com/unclebob/Acceptance-Pipeline-Specification` | `upstream/six-pack:roles/coder.prompt:9`, `hardender.prompt:9` |
| Session tracking | `entire enable …` + `entire agent add <backend>` per conf backend | `idea-K`, `mono §K` |
| ~~Skill pins~~ → **ADR 0018, not setup-swarm** | `entire` skills at pinned SHA (`install-pins.conf` `ENTIRE_SKILLS_SHA`); 11 skills + `agent-retro` to `.claude/skills/`. **Moved out of setup-swarm (decision 2026-06-14):** this is launcher infra-bootstrap, auto-installed by `./swarm` (`ensure_skills_installed`, pin-aware). Documented in **ADR 0018 (Idea N)**. | `mono §Idea N:100` |
| Permissions | write to `.claude/settings.json`: `Bash(gh pr merge*)` (integrator), `Bash(git reset --hard origin/<default-branch>)` (specifier) | `mono §Idea O:334` |
| Install scaffold | `.gitignore` ← `logbook.jsonl`, `tmp/`, `.swarmforge/`; default-branch probe `git symbolic-ref refs/remotes/origin/HEAD` → `swarmforge.conf` | `mono §Idea O:330-332` |

Note four-pack equivalents exist (architect/refactorer/coder) but four-pack is **frozen** — six-pack rows above are what matters.

## Swarm-ready marker

- Path **`.swarmforge/setup-complete`**; `./swarm` checks it before role launch; absent → refuse (ADR 0003 form). Operator deletes to force re-run. — `idea-K`, `mono §K:180`, `ADR`.
- **Marker content (defaulted 2026-06-14, impl detail):** timestamp + swarmforge SHA (debuggable); refusal message text is impl-level. Not an ADR decision.

## Open design questions — resolved 2026-06-14

1. **Stack detection mechanism** — **RESOLVED: the skill's own domain, not an ADR decision.** setup-swarm is a *skill* precisely because it *reasons* about the stack; the ADR must not prescribe a rigid probe list (that would contradict why it's a skill). The `SKILL.md` reads the repo, infers the stack, and asks the operator only when genuinely ambiguous.
2. **Marker format** — defaulted (see above): timestamp + swarmforge SHA. Impl detail.
3. **How the skill ships** — path `swarmforge/skills/setup-swarm/SKILL.md`, mirroring `agent-retro`. Settled.
4. **Re-run / staleness trigger** — RESOLVED: *project* re-setup = operator deletes the marker (manual, by design). *Skill* staleness = `./swarm` auto-(re)installs pin-aware at launch (ADR 0018), no manual trigger needed.
5. **Idea O scope boundary** — RESOLVED: setup-swarm absorbs `.gitignore`/default-branch/permissions (Idea O); the **`entire` skill install moved OUT to ADR 0018** (launcher bootstrap). No `./swarm install` subcommand.
6. **Per-language tool selection** — **RESOLVED: the skill's domain (same as #1).** The skill reasons from `engineering.prompt`'s tool table; behavior on no-match is skill-level judgment (ask the operator), not an ADR rule.

## Cross-references

- Pairs with **Idea N (install/upgrade)** and **Idea O (install scaffold)** — both implemented pre-reset, both **without an ADR**; see the manifest's "Uncaptured implemented divergences" section. The setup skill overlaps their territory and must be designed jointly.
- The removed "At startup" lines are also removed for the idle-gate reason (ADR 0002) — shared seam.
</content>
