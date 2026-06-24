---
status: accepted
---

# Role worktrees auto-compact before context overflow

A swarm role can run a long, many-turn session — build, run the suite, read failures, fix, re-verify — that walks its context toward the model's window limit. Upstream leaves context management to the client's defaults. The fork provisions each role worktree so the role **compacts its own context before it overflows** rather than failing partway through a task.

**The settings.** Each worktree's `.claude/settings.local.json` is given `autoCompactEnabled: true`, `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE: "88"` (compact at 88% of the window) and `CLAUDE_CODE_AUTO_COMPACT_WINDOW: "200000"`. The thresholds are tunable; these are the fork's current defaults, set to leave headroom ahead of a hard limit so compaction happens on the role's terms, not as a crash.

**Why per-worktree `settings.local.json`.** The file is fork-owned and not upstream-tracked, so writing to it adds no merge-conflict surface — the additive divergence ADR 0001 asks for. It is also the same provisioning seam already used to write the per-role advisor (ADR 0012); both perform a read-modify-write into this one file, so they share a single mechanism rather than each inventing its own.

## Pending implementation

- `main`: write the three keys into each worktree's `.claude/settings.local.json` (a `write_worktree_permissions` step, or folded into the existing advisor writer), called from `prepare_worktrees`; share the read-modify-write with the ADR 0012 advisor writer. Source: `backup/main-pre-reset` (`write_worktree_permissions`, ~L679; commit `93f8c5d`).
