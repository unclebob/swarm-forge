---
status: accepted
---

# Harness-enforced holdout of the QA suite

Upstream holds the end-to-end QA suite back from the coder by prompt instruction alone: the coder's prompt says "ignore the specifier's end-to-end QA suite," but the files sit in the coder's own worktree (every worktree is `git worktree add -B … HEAD`, a full checkout of the commit the specifier wrote the suite into). The wall is honor-system. The fork makes it **mechanical**: the QA suite is physically absent from the worktree of every role that shapes the implementation, so "ignore it" becomes "cannot reach it."

**Why mechanical, not instructional.** The verification-loop reference is explicit that the scenario suite is a *holdout* — "never visible to the code generation agent" — and names the failure mode directly: "holdout leakage … must be enforced architecturally (filesystem isolation, separate repos, access controls)," not by a prompt. A holdout the implementer can read is a holdout the implementer can quietly fit to; the suite then stops being a blind test and QA running it proves nothing. This is the prevention layer that the detection layers (mutation testing + refuting QA, ADR 0005) cannot supply: detection catches a gamed suite after the fact; the wall stops the gaming.

**Mechanism: `git sparse-checkout`, not file deletion.** The worktree-prep step the harness already runs sets a sparse-checkout on each role worktree that excludes the QA-suite path. Sparse-checkout makes the file *absent from disk but still tracked in the commit* — so the role cannot read it, yet its commit cannot accidentally drop it downstream. Naive deletion (`rm` from the worktree) was rejected for exactly this reason: the role commits with `git add`, the deletion gets staged, and the suite vanishes for QA. A separate QA-only branch was rejected as more flow change for no extra protection.

**Scope: hide from implementers, keep for author and verifier.** The exclusion applies to every worktree *except* the **specifier's** (it authors the suite) and **QA's** (it runs the suite — it is the verifier). Key the exclusion on the specifier *role*, not a fixed worktree name: it is the `master` worktree on upstream today, but ADR 0008 moves the specifier to its own `specifier` worktree, and this rule must follow it. Coder, UX Engineer, cleaner, architect, and hardener all touch the implementation before QA and so are walled. The integrator never touches implementation; its worktree is irrelevant either way.

**Precondition: a fixed QA-suite path.** For the harness to exclude the suite it must live at a deterministic path; the specifier writes the end-to-end QA suite under a pinned location (e.g. `qa/`). This is the only added convention. The existing coder-prompt "ignore it" line stays as defense-in-depth.

**Scope boundary: only the end-to-end QA suite.** The Gherkin acceptance tests and the acceptance pipeline stay fully visible — the coder builds and runs them. The holdout is the specifier's end-to-end QA suite alone.

## Pending implementation

- Add the sparse-checkout exclusion to the worktree-prep step (`six-pack`/scripts), keyed to skip the specifier's worktree (whatever its name — `master` today, `specifier` once ADR 0008 lands) and QA's.
- Pin the end-to-end QA-suite path in the specifier prompt.
- Confirm sparse-checkout interacts cleanly with the coder→cleaner→…→QA handoff commits (the excluded path must survive each role's commit untouched).
