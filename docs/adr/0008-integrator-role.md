---
status: accepted
---

# Integrator role lands work behind a CI gate

Upstream has no integrator: when QA signals done, the **specifier** merges the work ad hoc (a local `git merge`) and asks for the next feature. There is no gate between "QA passed" and "landed on the main branch." The fork adds a dedicated **integrator** as the terminal stage of the line that owns *landing* the work — and nothing lands except through a green CI gate.

**Landing is PR + CI, with no fallback.** From the QA-approved commit the integrator opens a pull request, watches CI, and merges only when CI is green; then it runs a **post-merge gate** — it watches the resulting main-branch CI run and, if the project defines a full verification suite, runs that on green too — before handing off. It never merges locally — a local merge is exactly what the specifier already did, so the integrator's whole value is that the main branch only ever receives green-CI'd work. **CI is therefore a hard precondition, not optional:** a project without CI is not swarm-ready, and ensuring CI is in place belongs to project setup (`0003`).

**It hands off to the curator.** The integrator is the last *code* stage, but not the last stage: on a green landing it notifies the **curator** (ADR 0013), which promotes the run's retro knowledge and only then releases the specifier for the next feature.

**One PR per feature.** Rework updates the same PR; a second PR is never opened for the same feature.

**Failure routing reuses back-routing.** A CI failure routes to the role that owns it — a failing test to the coder, a failing cleanliness gate to the cleaner, a failing architecture check to the architect; a trivially autofixable failure (lint/format) the integrator fixes in place on the PR branch and re-runs. This is the back-routing rule already decided (`0004`) with the integrator as the finder, capped at N=3 (`0004`): it tracks the cycle depth by counting its own failure comments on the PR, and after three it posts a final `FAILED: depth cap reached` comment and stops rather than looping. The post-merge gate's CI-red is routed the same way as pre-merge.

**The specifier stops merging.** Merging moves entirely to the integrator, so the specifier no longer needs the main checkout — it moves from the `master` worktree to its own worktree and starts each feature from a clean reset to the default branch.

## Pending implementation

- Runnable branches (`six-pack`; `four-pack` where present): new terminal `integrator` role; `swarmforge.conf` window; specifier worktree change and removal of its merge step.
- The PR/CI mechanism (platform, e.g. `gh`) named at implementation.
- CI-in-place enforced as a setup precondition (`0003`); routing per `0004`.
- Terminal handoff target is the curator (`0013`), not the specifier; autofixable lint/format is the integrator's only allowed code change.
