# Idea J — Session Retro via `entire` + `agent-retro`

**Status:** Decision — Pending Implementation
**Depends on:** Idea K (`entire` must be enabled and hooks installed before sessions are tracked)
**Design decisions:** docs/adr/0001-fork-divergence.md § "Design decisions: Idea J"

## What to implement

1. Update `agent-retro` Step 1 (transcript extraction) with an `entire`-backed primary path:
   - Run `entire session current` to get the active session ID
   - If found: `entire session info <id> --transcript > /tmp/retro-session.jsonl`, then pass that file to `extract.py`
   - Fallback: existing `~/.claude/projects/` path (Claude Code only)
   - Always write to a temp file — never stream transcript bytes inline into context (raw JSONL is 1MB+ per session)

2. Add `agent-retro before idle` as the final step to each role prompt on `four-pack` and `six-pack` (if not already present).

---

## Files changed

| File | Branch | Change |
|------|--------|--------|
| `agent-retro` skill `SKILL.md` (project-local, installed by operator) | target project | Step 1: `entire`-backed extraction path with `~/.claude/projects/` fallback |
| `swarmforge/roles/*.prompt` | `four-pack` | Add `agent-retro before idle` as final lifecycle step (4 roles) |
| `swarmforge/roles/*.prompt` | `six-pack` | Add `agent-retro before idle` as final lifecycle step (6 roles) |
