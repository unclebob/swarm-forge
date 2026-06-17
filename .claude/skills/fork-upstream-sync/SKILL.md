---
name: fork-upstream-sync
description: Sync this fork with new commits from unclebob/swarm-forge upstream. Identifies real conflicts (only files both sides modified since merge base), checks whether fork changes are superseded, migrates divergences to fork.bb, and opens a PR to gabadi/swarm-forge. Use when upstream has new commits, user says "sync upstream", "upstream has changes", or "merge upstream".
---

# Fork Upstream Sync

## Core rule: only intersecting files are real conflicts

After fetching upstream, find what each side changed since the merge base:

```bash
MERGE_BASE=$(rtk git merge-base HEAD upstream/main)
rtk git diff "$MERGE_BASE"..HEAD --name-only          # our side
rtk git diff "$MERGE_BASE"..upstream/main --name-only  # upstream side
```

Files only upstream changed → trivial forward merge, don't mention them.
Files only we changed → no conflict.
**Files in both lists → real conflicts. Analyze each one.**

## Classify each real conflict

For every intersecting file, answer in order:

1. **Superseded?** Read upstream's new version and grep for the intent of our change. If upstream solved it already (even differently), our change is moot — take theirs.
2. **Non-overlapping edits?** If our edit and upstream's are on different lines, take both — no decision needed.
3. **Migration needed?** If upstream rewrote/replaced the file and we have substantive logic in it, extract our logic to `fork.bb` (see below).

## Migration pattern: fork.bb

When upstream replaces a script file we've extended, move our logic into `swarmforge/scripts/fork.bb`. This file is loaded by `swarmforge.bb` via `(load-file ...)` and is 100% fork-owned — zero conflict surface on future syncs.

- Extractable: self-contained functions (settings writers, skill installers, sparse-checkout setup, prompt bundle resolvers)
- Must stay in `swarmforge.bb`: config parsing for new fields, permission-mode flags, setup guards, load-file call itself
- Keep the upstream file edits to small, stable hook call sites only

## Resolving constitution/articles conflicts

These files get edited by both sides frequently. Always:
- Take upstream's structural/wording changes
- Preserve our fork-specific rule insertions (check `rtk git diff "$MERGE_BASE"..HEAD -- <file>` to see exactly what we added)
- Never remove an upstream rule unless there's an explicit ADR for it

## Doing the merge

```bash
rtk git checkout -b feat/upstream-sync
rtk git merge upstream/main
# Files to take entirely from upstream:
rtk git checkout --theirs <file> && rtk git add <file>
# Manual resolutions: edit file, then rtk git add
rtk git commit
rtk git push origin feat/upstream-sync
gh pr create -R gabadi/swarm-forge --title "..." --body "..."
```

**Never** open a PR against `unclebob/swarm-forge`. Always target `gabadi/swarm-forge`.
`gh` CLI defaults to upstream — always pass `-R gabadi/swarm-forge`.

## After the PR

If any migrated divergences aren't yet documented, add an ADR row or manifest entry. ADR house style: divergence + why only, no rejected-options section.
