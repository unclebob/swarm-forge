---
status: accepted
---

# Rework routes back to its cause

Upstream fixes a problem wherever it is found — the QA role's prompt says plainly "fix bugs found by the QA suite." That keeps the line moving but lets fixes pile up downstream of the stage that caused them, and the responsible stage never learns it did its job wrong. The fork instead sends the work **back to the stage whose decision it exposes as flawed**, so the fix lands at the cause.

The trigger is not only a defect. Any finding that an earlier stage's work must change routes back — a failing behavior (a bug), a refactor blocked because the structure rests on a bad earlier decision, or a design/spec revision surfaced when a later stage tries to hold a behavior the specification can't carry. A defect is the most obvious case, not the only one.

**Only structural rework routes back.** It routes back when resolving it means re-opening an earlier stage's job — an ambiguous or missing specification, a weak or missing acceptance test, a design that can't hold the behavior. The stage that owns that work gets it back and corrects the root cause. **Local** work — anything the finder can resolve without re-opening an earlier stage's decision — stays with the finder. Routing a contained, local change backward only adds a round trip and teaches no one.

**Rework routes back at most once.** If it comes back still unresolved, the finder resolves it in place and flags it. This caps the cost and stops two stages volleying the same item indefinitely.

## Considered options

- **Route every finding back to its origin** — rejected: the line ping-pongs and a trivial local change becomes a round trip that teaches nothing; the cost is paid for findings that don't carry a lesson.
- **Keep upstream's fix-in-place** — rejected: rework accumulates as downstream patches and the stage that caused it is never corrected, so the same class of problem recurs.

## Pending implementation

- How a finding is attributed to an origin stage (the line must be able to trace it back to the spec, test, or design that owns it).
- Where the rule lives in the role prompts (runnable change, `six-pack`).
