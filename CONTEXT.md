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
Sending rework back to the stage whose decision it exposes as flawed, instead of resolving it where it was found. The trigger is any finding that an earlier stage's work must change — a bug, a refactor blocked by a bad earlier decision, or a design/spec revision. Applies only to _structural_ rework (re-opening an earlier stage's job: an ambiguous/missing spec, a weak/missing test, a design that can't hold the behavior); _local_ work the finder can resolve without re-opening an earlier decision stays with the finder. Two caps: a single finding bounces back at most once, and a feature tolerates at most three back-route cycles total (N=3, tracked by a routing count in the handoff) before the role stops and asks the user. (Upstream fixes everything in place.)
_Avoid_: rejection, escalation, bounce, defect back-routing

**QA holdout**:
The end-to-end QA suite kept physically out of reach of every role that shapes the implementation, so it stays a blind test. The harness sparse-checks-out the suite's pinned path from each role worktree except the specifier's (which authors it) and QA's (which runs it) — present in the commit, absent from disk. Distinct from upstream's prompt-level "ignore it," which leaves the files in the coder's worktree. Covers only the end-to-end QA suite; the Gherkin acceptance tests stay visible because the coder builds and runs them. (Upstream walls it by instruction only.)
_Avoid_: hidden tests, secret suite, test isolation

**Spec header**:
The structured block of comment sections the specifier fills in at the top of every feature file, above the Gherkin scenarios — the spec-authoring layer that states what the scenarios cannot: contract, constraints, sequencing, NFRs, side effects, scope (and, six-pack only, _UX Intent_). The scenarios are the contract by example; the spec header is the contract's surrounding intent. Every section is addressed; several default to `none` (a deliberate answer). Comments only, so the Gherkin parser ignores them. (Upstream feature files are pure Gherkin with no header.)
_Avoid_: preamble, comment block, feature description

**Surface harness**:
The way the live-verification roles (QA always; the _UX Engineer_ on six-pack) drive the running system through its real production interface — a declared per-surface tool (tmux/PTY for a TUI, Playwright for web, an HTTP client for an API, event injection for a headless service) chosen from the constitution's surface tool table. Replaces upstream's mechanically-silent "through the user interface only," which let in-process function calls pass as interface verification. Every surface also carries a _baseline scenario_. The role identifies the surface from the codebase; nothing declares it in `project.prompt`.
_Avoid_: UI test, e2e harness, driver

**Baseline scenario**:
The permanent idle/no-op scenario committed alongside a surface's flow scenarios, asserting the system is stable when nothing is happening — TUI: no input, identical consecutive captures, zero scrollback growth; web: idle load with no console errors; headless: a no-op event changes no state. It catches idle-state defects that flow scenarios never observe because flow scenarios only assert while the user is acting.
_Avoid_: smoke test, idle test, sanity check

**Observation harness**:
The project `observation-harness/` directory holding the committed, re-runnable surface scenarios — the per-surface _baseline scenario_ plus one set per verified flow — that form the permanent regression record. Authored by the live-verification role (the _UX Engineer_ on six-pack) using the _surface harness_ tool, and re-executed by QA before final verification; a user-facing surface with no scenarios is a finding that routes back. (Upstream has no such artifact.)
_Avoid_: e2e folder, regression dir

**Fidelity manifest**:
The constitution sub-file (`dependency-manifest.prompt`) declaring every dependency beyond the system itself by _dependency tier_, each as `name: tier N; implementation; gaps: <description or none>`. A declared gap is binding: the specifier and QA refuse to write or accept any scenario that rests on it, so a known emulator limitation can never pass as covered behavior. Specifier-owned; defaults to `(none)`.
_Avoid_: mock list, dependency doc, services file

**Dependency tier**:
The fidelity level at which a dependency is provided, declared in the _fidelity manifest_. Tier 1 — owned infrastructure run locally as the real engine (Postgres in Docker); tier 2 — stateful protocol-level emulation (vendor-official > third-party > swarm-built twin as last resort); tier 3 — external domain the swarm does not own, wire-level stubbed against a referenced contract. The system itself is always implicit, never a tier.
_Avoid_: mock level, fidelity grade

**Curator**:
The terminal role, after the integrator, that turns a run's session retros into versioned repo knowledge via one self-merging PR, then releases the specifier for the next feature. Makes no code changes — writes only _promoted knowledge_. An empty run notifies the specifier immediately; the line never stalls on it. (Upstream has no such role; lessons live only in unread retros.)
_Avoid_: librarian, archivist, scribe

**Promoted knowledge**:
The project-versioned knowledge contract the _curator_ writes and the launcher injects into role bundles: a root `AGENTS.md` (universal invariants + navigation) and `.agents/` (per-role files, references, skills, the enforcement-gate backlog, the _knowledge ledger_). Lives in the repo, not `~/.claude`, so a fresh clone carries every lesson. `AGENTS.md` and the role's file are injected into that role's bundle at launch; references load on demand by pointer. (Upstream bundles only the constitution and role prompt.)
_Avoid_: docs, memory, knowledge base

**Knowledge ledger**:
`.agents/ledger.md` — the append-only audit the _curator_ writes, one never-pruned line per processed retro item (`date | session-id | role | failure-class | verdict`). Makes recurrence provable: an item rejected before and seen again has proven itself worth promoting.
_Avoid_: changelog, history, log
