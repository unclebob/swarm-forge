---
status: proposed
---

# Harness-enforced holdout of the QA suite

**Open item — not decided.** Recorded so the gap is visible; needs a decision before any work.

Upstream already holds the end-to-end QA suite back from the coder: the coder's prompt says "ignore the specifier's end-to-end QA suite." But the wall is **honor-system only** — roles run in separate worktrees, yet the coder bases its work on the specifier's accepted commit, and the QA suite is part of that commit. The files sit in the coder's own working tree; nothing but a prompt instruction stops it from reading them.

The "AI Software Factory" reference argues a reachable validation criterion is a gamed one — "if the coding agent can see the tests, it will game them" — so the protection that counts is *mechanical*, not instructional. This item proposes making the holdout **harness-enforced**: the QA suite is physically absent from the coder's reach (for example, the specifier commits it on a path or branch the coder never bases on, or the harness strips QA-suite files from the coder's worktree), so "ignore it" becomes "cannot reach it."

It is filed as a candidate, not a decision, because enforcing a true holdout in a shared-git, peer-role swarm is non-trivial and may not be worth its cost: the fork already backs the visible test layers with mutation testing and an adversarial (refuting) QA suite, which is detection rather than prevention. Whether to add prevention on top is the open question.

## Open questions

- Can the QA suite be kept out of the coder's worktree without breaking the specifier→coder→QA handoff flow (the coder must still build against the spec, just not the QA suite)?
- Is harness-enforced prevention worth it given the fork already has mutation + refuting QA as detection?
- Does the same concern apply to the Gherkin acceptance tests, or only the QA suite? (The coder must see and build the Gherkin runner, so those likely cannot be walled off.)
