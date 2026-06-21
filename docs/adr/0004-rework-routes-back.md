---
status: accepted
---

# Rework routes back to its cause

Upstream fixes a problem wherever it is found — the QA role's prompt says plainly "fix bugs found by the QA suite." That keeps the line moving but lets fixes pile up downstream of the stage that caused them, and the responsible stage never learns it did its job wrong. The fork instead sends the work **back to the stage whose decision it exposes as flawed**, so the fix lands at the cause.

The trigger is not only a defect. Any finding that an earlier stage's work must change routes back — a failing behavior (a bug), a refactor blocked because the structure rests on a bad earlier decision, or a design/spec revision surfaced when a later stage tries to hold a behavior the specification can't carry. A defect is the most obvious case, not the only one.

**Only structural rework routes back.** It routes back when resolving it means re-opening an earlier stage's job — an ambiguous or missing specification, a weak or missing acceptance test, a design that can't hold the behavior. The stage that owns that work gets it back and corrects the root cause. **Local** work — anything the finder can resolve without re-opening an earlier stage's decision — stays with the finder. Routing a contained, local change backward only adds a round trip and teaches no one.

**Two caps, at two scopes.** A *single finding* routes back to its cause **at most once**: if it returns still unresolved, the finder resolves it in place and flags it, so two stages never volley the same item. Independently, a *feature* tolerates **at most three back-route cycles total** (depth cap N=3), tracked by a routing count carried in the handoff trail; after the third the routing role stops and asks the user rather than looping. The first cap stops ping-pong on one issue; the second stops a feature from churning through endless distinct bounces. (The role prompts — ux-engineer, integrator — carry the N=3 feature-level cap.)

## Pending implementation

- How a finding is attributed to an origin stage (the line must be able to trace it back to the spec, test, or design that owns it).

## Implementation notes

- Back-routing always uses `git_handoff` with the sender's current branch HEAD as the commit, even when the sender authored no functional lines. Two distinct rules (forwarding vs. back-routing) replace the old single "no functional change" block in `swarmforge/constitution/articles/handoffs.prompt`.
