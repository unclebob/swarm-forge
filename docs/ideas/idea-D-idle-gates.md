# Idea D — Role Idle Gates

**Status:** Decision — Pending Implementation
**Depends on:** Idea A (delivery sequence re-sends bundle on every handoff, making startup directives fire every task)
**Design decisions:** docs/adr/0001-fork-divergence.md § "Design decisions: Idea D"
**Domain vocabulary:** CONTEXT.md — Handoff, Idle gate

## What to implement

1. Add the following line to every role prompt on both `four-pack` and `six-pack`, at the top of the role rules: "Wait for a handoff. Do not act without one."
2. Remove all "At startup, install/build X" lines from every role prompt on both branches.

---

## Files changed

| Branch | File | Change |
|--------|------|--------|
| `four-pack` | `swarmforge/roles/coder.prompt` | Add idle gate; remove startup install directive |
| `four-pack` | `swarmforge/roles/architect.prompt` | Add idle gate; remove startup install directives (mutation tool, gherkin tools, DRY tool) |
| `four-pack` | `swarmforge/roles/refactorer.prompt` | Add idle gate; remove startup install directive |
| `four-pack` | `swarmforge/roles/specifier.prompt` | Add idle gate |
| `six-pack` | `swarmforge/roles/coder.prompt` | Add idle gate; remove startup install directive |
| `six-pack` | `swarmforge/roles/architect.prompt` | Add idle gate |
| `six-pack` | `swarmforge/roles/QA.prompt` | Add idle gate; remove startup install directive |
| `six-pack` | `swarmforge/roles/cleaner.prompt` | Add idle gate; remove startup install directive |
| `six-pack` | `swarmforge/roles/hardender.prompt` | Add idle gate; remove startup install directives (tools + gherkin tools) |
| `six-pack` | `swarmforge/roles/specifier.prompt` | Add idle gate |
