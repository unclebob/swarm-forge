---
status: accepted
---

# Roles run unattended in autonomous permission mode

Upstream launches the `claude` and `grok` roles with `--permission-mode acceptEdits`, which auto-approves file edits but still raises an interactive permission prompt on every bash/tool call. The fork's roles run **fully unattended** in isolated worktrees — there is no human present to answer that prompt, so for the fork the prompt is not a safety net, it is a silent hang. The fork launches with `--permission-mode auto`.

**Why `auto` and not the other never-prompt modes.** Claude Code offers three modes that never block on a prompt: `auto`, `dontAsk`, and `bypassPermissions`. `bypassPermissions` ignores all allow/deny rules and ships no safety checks — unacceptable for worktrees that touch a real repository and the network. `dontAsk` is deterministic but runs only an explicit allow-list and denies everything else, which would mean building and maintaining an exhaustive command allow-list spanning every language and tool the swarm drives — ongoing complexity the fork chooses not to take on. `auto` keeps roles moving with near-zero configuration while retaining built-in guardrails (it still refuses force-pushes to the main branch, mass deletion, and similar high-blast-radius actions). Because `auto` is in force, the permission allow-rules that `setup-swarm` writes (ADR 0003) stay a small, targeted, advisory set rather than a load-bearing whitelist.

**This is a real mode, deliberately verified.** `auto` is one of Claude Code's documented `--permission-mode` values — unlike the per-role advisor knob of ADR 0012, which turned out to have no CLI flag and had to be written to settings instead. The lesson there was applied here before committing to the divergence.

The `codex` backend launches with no permission-mode flag at all, so this change touches only the `claude` and `grok` launch lines.

## Pending implementation

- `main`: change `--permission-mode acceptEdits` → `auto` on the `claude` and `grok` lines in `launch_role` (a one-word change on each line; reapply on every upstream sync). Source: `backup/main-pre-reset` commit `1097233`.
