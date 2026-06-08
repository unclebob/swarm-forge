# Idea K — Setup / Preflight

**Status:** Decision — Pending Implementation
**Required by:** Idea J (`entire` must be enabled before sessions are tracked)
**Design decisions:** docs/adr/0001-fork-divergence.md § "Design decisions: Idea K"

## What to implement

1. In `./swarm`, before role launch: check for `.swarmforge/setup-complete`. If it exists, skip setup.

2. If not present, run setup:
   - `entire enable --no-github --telemetry=false` — enable entire non-interactively in the project repo
   - Parse unique agent backends from `swarmforge.conf` (column 3 of each `window` line)
   - For each unique backend: `entire agent add <backend>`
   - Write `.swarmforge/setup-complete` sentinel
   - If `entire` is not installed: warn and continue — retros will run without trace backing

3. No `./swarm setup` subcommand needed — operator deletes `.swarmforge/setup-complete` manually to force re-run.

---

## Files changed

| File | Branch | Change |
|------|--------|--------|
| `./swarm` | `four-pack`, `six-pack` | Preflight block before role launch |
