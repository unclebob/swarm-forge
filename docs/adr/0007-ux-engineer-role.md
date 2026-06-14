---
status: accepted
---

# UX Engineer role and UX Intent

Upstream has no UX role — nothing in the line owns whether the product is *usable*, only whether it is correct. The fork adds a **UX Engineer** (six-pack only) that runs the built product and **fixes** visual and usability mismatches in rendering code, leaving a regression check behind. It is an engineer, not a flag-only reviewer: the fork's pattern is that every stage fixes in place and leaves a durable artifact, so a report-only role is the anti-pattern it rejects.

**It checks against UX Intent.** The specifier authors a **UX Intent** section inline in the feature file — concrete, observable statements of what the feature should look and feel like. UX Intent is part of the swarm and travels with the feature. A feature with no UX Intent is the signal to skip: the UX Engineer passes straight through to the next stage, the same "no work, no handoff" pattern used elsewhere.

**Optional design inputs are referenced, not owned.** When a project supplies design artifacts — a DESIGN.md (visual system), an EXPERIENCE.md (interaction and feel), mockups (concrete visual targets) — the specifier **references** them from the feature file, and the UX Engineer consults them alongside UX Intent. These are optional project inputs; the swarm neither defines, scaffolds, nor requires them. This replaces the earlier design's automatic "nearest-file" resolution with an explicit reference from the one canonical artifact.

**Framework-agnostic.** The role defines the *class* of check — the running product matches its stated UX — and leaves the specific visual-testing tool to the project's constitution. No terminal-UI assumptions live in the role.

**Placement and routing.** The UX Engineer sits immediately after the coder, so the downstream roles (cleaner, architect, hardener, QA) see implementation and rendering code together in one pass rather than running twice. When a mismatch cannot be fixed in rendering alone and needs a model-state change, it routes back to the coder — using the back-routing rule already decided (`0004`), not a separate mechanism.

## Considered options

- **A flag-only UX reviewer** — rejected: produces a handback with no durable artifact; the fork's pattern is fix-in-place.
- **The swarm owns/scaffolds DESIGN.md and friends** — rejected: those are optional project inputs, referenced not owned; the swarm should not impose a design system.
- **Automatic nearest-file resolution of design docs** — superseded: explicit references from the feature file are clearer and need no walk-up.
- **Place the UX role late (after the hardener)** — rejected: prior batch evidence showed it made the cleaner, architect, and hardener each run twice per feature.

## Pending implementation

- Six-pack only: new `ux-engineer` role prompt; UX Intent authoring in the specifier and the feature template; coder reads UX Intent; `swarmforge.conf` adds the window after the coder.
- Routing follows `0004`.
