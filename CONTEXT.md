# SwarmForge Fork

A permanent fork of `unclebob/swarm-forge` (rationale in `docs/adr/`). This glossary holds only terms whose fork-specific meaning is already settled; terms are added as decisions are made, not in advance.

## Language

**Idle gate**:
The rule that a role does nothing until it receives a handoff — no startup work, scanning, installing, or self-assigned tasks. The single line is "Wait for a handoff. Do not act without one."
_Avoid_: startup guard, wait condition

**Ready notification** (presence signal):
The startup "I'm awake" message each role sends to the specifier. Informational only — it tells the operator the role launched. Stamped a distinct `presence` type and excluded from the _Delivery sequence_; in the fork's idle model readiness is implicit (a role at idle with an empty queue is ready).
_Avoid_: awake handoff, ready handoff

**Delivery sequence**:
The steps that start a work handoff on a receiver: `/clear` → re-inject the role bundle → send the task message. Runs for work handoffs only, never for presence pings. Delivered immediately if the receiver is idle, or by its Stop hook when it next stops if busy. (Upstream instead types the message straight into the terminal with no clear.)
_Avoid_: inject, dispatch

**Setup skill**:
The one-time, stack-aware step that makes a project swarm-ready — installs the project's language quality tools, enables session tracking, grants the agents' permissions, pins skill versions. Ships inside the swarm install and is the first thing the operator runs. The run path (`./swarm`) does no project setup; it stops if the skill has not run. (Upstream instead installs tooling per-role at startup.)
_Avoid_: preflight, bootstrap, onboarding

**Integrator**:
The terminal role that lands finished work. From the QA-approved commit it opens a pull request, gates on CI, merges only on green, runs the post-merge verification, and notifies the specifier — one PR per feature. It never merges locally: CI is a hard precondition, so a project without CI is not swarm-ready (setup ensures CI; see [[project-fork-divergence-adr-structure]] / ADR 0003). CI failures route to the owning role via [[back-routing]]. (Upstream has no integrator — the specifier merges ad hoc.)
_Avoid_: merger, releaser, deployer

**UX Engineer** (six-pack only):
The role, immediately after the coder, that runs the built product and fixes visual/usability mismatches in rendering code (leaving a regression check behind) — an engineer that fixes, not a flag-only reviewer. Checks against the feature's _UX Intent_ and any optional design inputs the feature references. Skips (passes through) when the feature has no UX Intent. Routes back to the coder via [[back-routing]] when a fix needs a model-state change. Framework-agnostic; the visual-testing tool is named by the constitution.
_Avoid_: UX Reviewer, designer

**UX Intent**:
The section the specifier authors inline in the feature file stating, in concrete observable terms, what a feature should look and feel like. Part of the swarm and the _UX Engineer_'s primary target. Distinct from optional project design inputs (DESIGN.md, EXPERIENCE.md, mockups) — those are not swarm-owned; the specifier merely references them from the feature file when they exist.
_Avoid_: design spec, UX requirements

**Refuting QA**:
QA's posture in the fork: assume the build does not meet the spec and the acceptance tests are too weak to notice, until proven otherwise — attack the specified contract rather than run a checklist and confirm. Bounded by the spec (unspecified gaps route back to the specifier, they are not QA pass/fail). Includes _conversion fidelity_: a QA procedure converted into an executable script must encode the procedure's full intent, not a green version that asserts nothing (_test theater_). (Upstream QA confirms the spec is met and fixes what fails.)
_Avoid_: verification, acceptance check, confirm

**Back-routing**:
Sending rework back to the stage whose decision it exposes as flawed, instead of resolving it where it was found. The trigger is any finding that an earlier stage's work must change — a bug, a refactor blocked by a bad earlier decision, or a design/spec revision. Applies only to _structural_ rework (re-opening an earlier stage's job: an ambiguous/missing spec, a weak/missing test, a design that can't hold the behavior); _local_ work the finder can resolve without re-opening an earlier decision stays with the finder. Routes back at most once. (Upstream fixes everything in place.)
_Avoid_: rejection, escalation, bounce, defect back-routing

**QA holdout**:
The end-to-end QA suite kept physically out of reach of every role that shapes the implementation, so it stays a blind test. The harness sparse-checks-out the suite's pinned path from each role worktree except the specifier's (which authors it) and QA's (which runs it) — present in the commit, absent from disk. Distinct from upstream's prompt-level "ignore it," which leaves the files in the coder's worktree. Covers only the end-to-end QA suite; the Gherkin acceptance tests stay visible because the coder builds and runs them. (Upstream walls it by instruction only.)
_Avoid_: hidden tests, secret suite, test isolation
