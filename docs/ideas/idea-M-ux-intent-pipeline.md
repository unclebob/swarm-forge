# Idea M — UX Intent in the SwarmForge Pipeline

**Status:** Decision — Pending Implementation
**Depends on:** Idea L (feature template), Idea J (agent-retro)
**Design decisions:** docs/adr/0001-fork-divergence.md § "Design decisions: Idea M"
**Domain vocabulary:** CONTEXT.md — UX Intent, UX Engineer

## What to implement

1. Add `## UX Intent` section to `swarmforge/templates/feature.feature` on six-pack, following the existing comment-section style (Ask: / Format:). Four subsections: Visual Composition, Information Hierarchy, Interaction Feel, State Transitions.

2. Update `swarmforge/roles/specifier.prompt` on six-pack — add before phase 1: "If the feature has UX requirements, author a `## UX Intent` section in the feature file covering Visual Composition, Information Hierarchy, Interaction Feel, and State Transitions. Write each as concrete observable statements, not subjective preferences."

3. Update `swarmforge/roles/coder.prompt` on six-pack — add: "If the feature file contains a `## UX Intent` section, implement from it alongside the Gherkin. The UX Intent specifies visual composition, information hierarchy, interaction feel, and state transitions the implementation must satisfy. If UX Intent and Gherkin conflict, stop and report."

4. Update `swarmforge/roles/hardener.prompt` on six-pack — add: "For pure rendering functions (state → string with no side effects), add property tests using the rendering invariant tool from the constitution: required structural elements always present for their states, character set bounded to declared vocabulary, mutually exclusive states never co-rendered." Change final notification from QA to ux-engineer.

5. Create `swarmforge/roles/ux-engineer.prompt` on six-pack (new):
   - Idle gate: wait for a handoff; do not act without one.
   - If the feature file has no `## UX Intent` section, notify QA immediately without changes.
   - At startup, install the golden file snapshot tool and rendering invariant tool from the constitution.
   - Read the feature file's `## UX Intent` section.
   - Run the binary; compare live experience against each statement across Visual Composition, Information Hierarchy, Interaction Feel, and State Transitions.
   - Fix mismatches in rendering code (layout, character vocabulary, state transitions).
   - Add golden file snapshots for each verified state.
   - Add rendering invariants for structural properties.
   - Run the test suite; fix any failures.
   - If a mismatch requires changing model state shape (cannot be fixed in rendering code alone): back-route to coder with a specific actionable message — what UX Intent says, what the current implementation does, what must change. Include routing count in the handoff. Depth cap N=3 (read from incoming handoff's routing count); after cap exhaustion, stop and ask the user.
   - `agent-retro before idle`.
   - Commit and notify QA.

6. Update `swarmforge/swarmforge.conf` on six-pack:
   - Fix typo: `hardender` → `hardener`
   - Add `window ux-engineer codex ux-engineer` between hardener and QA

---

## Files changed

| Branch | File | Change |
|--------|------|--------|
| six-pack | `swarmforge/templates/feature.feature` | Add `## UX Intent` section |
| six-pack | `swarmforge/roles/specifier.prompt` | Add UX Intent authoring step before phase 1 |
| six-pack | `swarmforge/roles/coder.prompt` | Add UX Intent reading instruction |
| six-pack | `swarmforge/roles/hardener.prompt` | Add rendering property tests; change notification to ux-engineer |
| six-pack | `swarmforge/roles/ux-engineer.prompt` | New file |
| six-pack | `swarmforge/swarmforge.conf` | Fix typo; add ux-engineer window |
