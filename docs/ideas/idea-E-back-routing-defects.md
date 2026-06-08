# Idea E — Back-Routing Defects

**Status:** Decision — Pending Implementation
**Depends on:** None
**Design decisions:** docs/adr/0001-fork-divergence.md § "Design decisions: Idea E"
**Domain vocabulary:** CONTEXT.md — Handoff

## What to implement

1. Add the following rule to `swarmforge/constitution/workflow.prompt` on both `four-pack` and `six-pack`: "When you discover a defect you do not own, route it back to the role that sent you this handoff. Include: the failing step, the raw error output, your diagnosis, and a repro recipe. Autofixable issues (formatting, linting) are excepted — fix those in place."

---

## Files changed

| Branch | File | Change |
|--------|------|--------|
| `four-pack` | `swarmforge/constitution/workflow.prompt` | Add back-routing rule |
| `six-pack` | `swarmforge/constitution/workflow.prompt` | Add back-routing rule |
