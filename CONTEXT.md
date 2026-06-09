# SwarmForge Fork

A permanent fork of `unclebob/swarm-forge` that adds a cmux backend and a set of hardened operational behaviours absent in upstream.

## Language

**Task**:
The unit of work for a role — from the moment the harness writes `executing` in its logbook to the moment it writes `executed` (when the role calls `notify-agent.sh`). One role executes at most one task at a time.
_Avoid_: job, turn, session

**Delivery sequence**:
The ordered steps the harness runs to start a task on a receiver role:
1. `git reset --hard <hash>` in receiver's worktree (commit hash from `[handoff]` trailer)
2. Send `/clear` to receiver's terminal
3. Sleep 1s (ensures `/clear` completes before next input)
4. Send `/rename SwarmForge <display-name>` (restores the role's original name, which `/clear` wipes; baked in at launch time)
5. Send the full resolved bundle (constitution + role prompt, cached at `.swarmforge/prompts/<role>.md`)
6. Send the task message
_Avoid_: inject, push, dispatch

**Logbook**:
A per-role, per-worktree `logbook.json` that tracks the current task state. Written by both the harness and the agent. Mutable — entries are updated as state progresses. Never committed.
_Avoid_: queue, state file, log

**Prompt bundle**:
The resolved set of constitution and role prompt files delivered to an agent at launch, wrapped in an XML envelope (`<swarmforge_agent_context role="...">`). Resolved once at launch via BFS from `constitution.prompt` then `<role>.prompt`; cached at `.swarmforge/prompts/<role>.md`. Identical content regardless of agent type — only the delivery channel differs.
_Avoid_: instruction file, context file, system prompt

**Bundle cache**:
The file `.swarmforge/prompts/<role>.md` — the pre-resolved prompt bundle written at launch time. Read by the delivery sequence (Idea A) on every handoff so the agent's instructions arrive complete on each task without re-resolving.
_Avoid_: prompt file, instructions file

**Landing**:
Getting work from a role branch onto `main` via a PR. Owned exclusively by the integrator — no other role merges to trunk.
_Avoid_: merge, ship, deploy

**Routing cycle**:
One full pipeline re-run triggered by the integrator after a CI failure. The integrator routes the failure to the owning role; when fixed, the pipeline runs forward again and the integrator receives a new handoff. Count is tracked via the integrator's own failure comments on the PR.
_Avoid_: retry, iteration

**Depth cap**:
The maximum number of routing cycles the integrator will attempt per feature before giving up (N=3). On exhaustion: leave a FAILED comment on the PR and go idle.
_Avoid_: retry limit, max retries

**Handoff**:
The message a role sends to the next role to pass work forward. Contains: sender role, specifier handoff name, branch name, and 10-character commit hash in a `[handoff]` trailer. The delivery sequence uses the commit hash to reset the receiver's worktree before delivering the message.
_Avoid_: notification, message, task assignment

**Idle gate**:
The single rule in each role prompt that prevents a role from acting without a handoff: "Wait for a handoff. Do not act without one." A role with no handoff does nothing — no scanning, installing, or self-assigned work.
_Avoid_: startup guard, wait condition

**DESIGN.md**:
The project-level persistent design contract. Defines aesthetic decisions that apply across all features: typography, color palette, spacing scale, component vocabulary, and universal design standards. Resolved via nearest-file: the UX Engineer and specifier walk up from the files being touched until they find one. In a monorepo, each app may carry its own DESIGN.md. Absent on first UX feature, the specifier scaffolds one and presents it for user approval before proceeding.
_Avoid_: design system file, style guide, design token file

**UX Intent**:
The `## UX Intent` section in the feature file, authored by the specifier before writing Gherkin. Covers four dimensions: Visual Composition, Information Hierarchy, Interaction Feel, and State Transitions. Written as concrete observable statements grounded in the project's DESIGN.md where one exists. Present only for features with UX requirements — its absence is the UX Engineer's signal to skip to QA immediately. Six-pack only.
_Avoid_: UX spec, design doc, UX requirements

**UX Engineer**:
The six-pack role between coder and cleaner. Reads UX Intent from the feature file and the nearest DESIGN.md, runs the binary, and fixes mismatches in rendering code. Has fix authority over violations of both — UX Intent (per-feature compliance) and DESIGN.md (project-level aesthetic consistency) — even when a violation is absent from UX Intent. Adds golden file snapshots and rendering invariants. On a mismatch requiring model state changes, back-routes to the coder with a specific actionable message; the full pipeline re-runs. Depth cap N=3 (tracked via routing count in the handoff message trail) — after three back-routes, stops and asks the user. If no `## UX Intent` section is present, notifies QA immediately without changes.
_Avoid_: UX Reviewer, visual reviewer, UX auditor

**Logbook statuses** (one logbook per role; the harness owns all writes — the agent writes nothing; `logbook.json` is always gitignored and never committed):
- `pending` — appended by `notify-agent.sh` into the TARGET role's logbook when that role is busy. Carries `{status, timestamp, message, hash, sender}`. Multiple entries may exist; delivered in order. `notify-agent.sh` never rejects.
- `executing` — written by the harness as step 0 of the delivery sequence (before `/clear`). Carries `{status, timestamp, message, hash, sender}` forwarded from the `pending` entry being delivered, so the role can recover its task if the session restarts mid-task.
- `sent` — written by `notify-agent.sh` to the CALLING role's own logbook; records target role, message content, and commit hash. Audit trail.
- `executed` — written by `notify-agent.sh` to the CALLING role's own logbook immediately after `sent`; signals the calling role's task is complete. Carries an optional `summary` field for structured task output (verification results, defect list, etc.). The Stop hook reads this as the idle signal to deliver the next `pending` entry.
