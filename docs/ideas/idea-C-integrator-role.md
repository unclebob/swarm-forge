# Idea C — Integrator Role

**Status:** Decision — Pending Implementation  
**Depends on:** Idea A (harness serializes delivery; Idea A must be implemented first)  
**Design decisions:** `docs/adr/0001-fork-divergence.md` § "Design decisions: Idea C"  
**Domain vocabulary:** `CONTEXT.md` — Landing, Routing cycle, Depth cap

## What to implement

Add an integrator role as the terminal stage of the pipeline, and move the specifier to its own worktree.

### Pipelines

**Four-pack:**
```
specifier → coder → refactorer → architect → integrator → (notify specifier)
```

**Six-pack:**
```
specifier → coder → cleaner → architect → hardener → UX Reviewer → QA → integrator → (notify specifier)
```

> UX Reviewer is added by Idea M (six-pack only). Whether it acts as a pure gate or also applies changes is deferred to the Idea M grilling session.

### Integrator lifecycle

1. Receives handoff from the terminal quality role (architect in four-pack; QA in six-pack) with commit hash. No handoff — including cold launch — means idle.
2. Creates a new per-feature branch: `git checkout -b feat/<initiative> <hash>`, naming `<initiative>` for the feature. Never pushes `swarmforge-integrator` as the PR head.
3. Opens the PR with `gh pr create`; then `git checkout swarmforge-integrator` so the feature branch can be deleted on merge. One PR per feature — rework updates the same PR, never opens a second.
4. Watches CI: `gh pr checks --watch` (background, halt for completion).
5. On green: merges (`gh pr merge --delete-branch`). Then post-merge gate: watch post-merge `main` CI with `gh run watch` (background, halt). If the project's constitution/engineering.prompt defines a full verification suite command, run it on green — skip if none defined.
6. Notifies specifier of completion.
7. On CI red (pre-merge or post-merge gate): diagnose then route — see CI-red routing below.
8. `agent-retro` before idle.

### CI-red routing

Routes directly to the owning role — does not use the hop-by-hop constitution rule (Idea E applies mid-pipeline only):

- Autofixable (lint/format): fix in-place on the PR branch, push, re-watch CI.
- Failing test → coder
- Failing coverage/CRAP/DRY → cleanliness role (refactorer in four-pack; cleaner in six-pack)
- Failing arch-check → architect
- Depth cap N=3: count the integrator's own failure comments on the PR. On third failure: leave FAILED comment with diagnosis, go idle.

### Specifier worktree change

The specifier moves from `master` to its own `specifier` worktree. First step of specifier lifecycle: `git reset --hard origin/main`. Merge instruction removed from specifier prompt — integrator owns all merging.

**swarmforge.conf change (both branches):**
```
window specifier <agent> specifier   # was: master
```

---

## Files changed

| Branch | File | Change |
|--------|------|--------|
| `four-pack` + `six-pack` | `swarmforge/swarmforge.conf` | Add integrator window; change specifier from `master` to `specifier` worktree |
| `four-pack` + `six-pack` | `swarmforge/roles/integrator.prompt` | New file |
| `four-pack` + `six-pack` | `swarmforge/roles/specifier.prompt` | Add reset-to-origin/main as first lifecycle step; remove merge instruction |
| `four-pack` | `swarmforge/roles/architect.prompt` | Notify integrator instead of specifier |
| `six-pack` | `swarmforge/roles/QA.prompt` | Notify integrator instead of specifier |
