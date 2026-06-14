---
status: accepted
---

# UX Engineer role and UX Intent

Upstream has no UX role — nothing in the line owns whether the product is *usable*, only whether it is correct. The fork adds a **UX Engineer** (six-pack only) that runs the built product and **fixes** visual and usability mismatches in rendering code, leaving a regression check behind. It is an engineer, not a flag-only reviewer: the fork's pattern is that every stage fixes in place and leaves a durable artifact, so a report-only role is the anti-pattern it rejects.

**It checks against UX Intent.** The specifier authors a **UX Intent** section inline in the feature file — concrete, observable statements of what the feature should look and feel like. UX Intent is part of the swarm and travels with the feature. A feature with no UX Intent is the signal to skip: the UX Engineer passes straight through to the next stage, the same "no work, no handoff" pattern used elsewhere.

**Optional design inputs are referenced, not owned.** When a project supplies design artifacts — a DESIGN.md (visual system), an EXPERIENCE.md (interaction and feel), mockups (concrete visual targets) — the specifier **references** them from the feature file, and the UX Engineer consults them alongside UX Intent. These are optional project inputs; the swarm neither defines, scaffolds, nor requires them, and does not walk the directory tree to discover them — the only link is an explicit reference from the feature file.

**It also enforces a universal visual-quality bar, independent of any project input.** Beyond UX Intent and any DESIGN.md, the role applies a fixed standard the prompt enumerates: AI-aesthetic anti-patterns (unjustified default purple/indigo, gradient noise, uniformly maximal rounding, oversized equal padding, shadow-heavy chrome, missing loading/error/empty states), type-hierarchy rules (primary content must dominate; no skipped heading levels), and colour rules including **WCAG contrast minimums (4.5:1 normal, 3:1 large) and "colour is never the sole state indicator."** These hold even when a feature has no UX Intent and the project has no DESIGN.md — they are the floor, not project preferences.

**It leaves a durable, re-runnable artifact.** "Fixes, leaves a regression check behind" is concrete: per verified flow the role commits re-runnable scenarios to `observation-harness/` using the project's surface tool (ADR 0010), plus golden-file snapshots per state and rendering invariants for structural properties. These are the permanent regression record and must pass against the committed code — and QA re-executes them downstream (ADR 0010), routing back here if a user-facing surface has none.

**Framework-agnostic.** The role defines the *class* of check — the running product matches its stated UX — and leaves the specific visual-testing tool to the project's constitution. No terminal-UI assumptions live in the role.

**Placement and routing.** The UX Engineer sits immediately after the coder, so the downstream roles (cleaner, architect, hardener, QA) see implementation and rendering code together in one pass rather than running twice. When a mismatch cannot be fixed in rendering alone and needs a model-state change, it routes back to the coder — using the back-routing rule already decided (`0004`), not a separate mechanism. The back-route message carries what UX Intent says, what the implementation does, what must change, and the current routing count; the role observes the N=3 feature-level cap (`0004`) and stops to ask the user after the third cycle.

## Pending implementation

- Six-pack only: new `ux-engineer` role prompt; UX Intent authoring in the specifier and the feature template; coder reads UX Intent; `swarmforge.conf` adds the window after the coder.
- Routing follows `0004`; durable artifact (`observation-harness/`, snapshots, rendering invariants) follows `0010`.
- DESIGN.md is referenced from the feature file only — the specifier does not scaffold it and the ux-engineer does not walk the tree to find it.
