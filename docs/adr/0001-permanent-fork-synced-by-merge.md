---
status: accepted
---

# Permanent fork of unclebob/swarm-forge, synced by merge

This repo is a permanent fork of `unclebob/swarm-forge` (remote `upstream`); nothing is contributed back. Upstream moves fast, so we keep current by **merging** `upstream/<branch>` into our branches — never rebasing — because the fork is published/shared and rebasing would rewrite shared history and re-surface every conflict on each sync. `git rerere` is enabled (`rerere.enabled`, `rerere.autoupdate`) so conflict resolutions replay automatically. Every divergence should be **additive** (a new file or an appended rule) and recorded as its own ADR in this directory; a non-additive edit to an upstream line is a conscious, documented cost. Two branches are maintained: `main` (shared scripts + these docs) and `six-pack` (runnable: role prompts, `swarmforge.conf`, templates).

Because the fork can be hard-reset back to a pristine upstream commit (see the `backup/*-pre-reset` branches), the merge history that would otherwise encode the integration point is not a dependable anchor. The upstream baseline each branch's fork layer is re-applied onto is therefore recorded **explicitly**: a SHA line in `docs/fork-change-manifest.md` and an annotated `fork-base/<date>-<branch>` tag at each sync, both surviving a reset.

Two merge styles, by source: **fork divergences are squash-merged** — every divergence PR lands as a single commit, so the fork layer reads as one clean, revertible, re-appliable commit per divergence. **Upstream is integrated by a history-preserving merge** — never squashed and never rebased, so upstream's commit story stays intact and `rerere`-replayable. A landed commit is never rewritten afterward.
