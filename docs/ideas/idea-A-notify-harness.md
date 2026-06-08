# Idea A — Notify Harness

**Status:** Decision — `swarmforge.sh` changes implemented on `main` (commit `08e7f25`); role prompt cleanup pending on `four-pack` and `six-pack`  
**Depends on:** Idea B (bundle cached at `.swarmforge/prompts/<role>.md` — implemented)  
**Design decisions:** `docs/adr/0001-fork-divergence.md` § "Design decisions: Idea A"  
**Domain vocabulary:** `CONTEXT.md`

## What to implement

Remove `pending-messages/` directory references and logbook-write instructions from all role prompts. The harness now owns all logbook writes; the agent's only obligation is to call `notify-agent.sh <target> "<message>"` when done.

---

## Role prompts on `four-pack` and `six-pack`

Remove all references to:
- `pending-messages/` directory
- Instructions to write `executing`, `executed`, or any logbook entry
- Instructions to process queued message files

The agent's only harness obligation is: call `notify-agent.sh <target> "<message>"` when the task is complete (after retro). The harness handles all state transitions.

---

## Files changed

| Branch | File | Change |
|--------|------|--------|
| `four-pack` | `swarmforge/roles/*.prompt` | Remove pending-messages rules and logbook write instructions |
| `six-pack` | `swarmforge/roles/*.prompt` | Same |
