# Idea L — Gherkin Header Sections

**Status:** Decision — Pending Implementation
**Design decisions:** docs/adr/0001-fork-divergence.md § "Design decisions: Idea L"

## What to implement

1. Copy `swarmforge/templates/feature.feature` from melech-mini-apps to `swarmforge/templates/feature.feature` on `four-pack` and `six-pack` (adopt as-is).

2. In the `four-pack` specifier prompt, update phase 1:
   - Before: "Write the Gherkin that specifies the feature."
   - After: "Write the Gherkin that specifies the feature. Start from `swarmforge/templates/feature.feature`; complete all seven header sections before writing scenarios."

3. Same update to the `six-pack` specifier prompt phase 1.

---

## Files changed

| File | Branch | Change |
|------|--------|--------|
| `swarmforge/templates/feature.feature` | `four-pack`, `six-pack` | New — 7-section rubric template (copied from melech-mini-apps) |
| `swarmforge/roles/specifier.prompt` | `four-pack`, `six-pack` | Phase 1: add template reference |
