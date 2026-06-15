# Handoff Daemon Proposal

## Goal

Replace direct agent access to the tmux socket with a daemon-owned file transport.
Agents should not send tmux commands, manage socket permissions, or maintain a
separate logbook. Agents should create small, validated handoff requests; the
daemon should deliver them through durable inbox files and send only wake-up
notifications through tmux.

## Summary

The swarm startup script starts a handoff daemon alongside the tmux session. The
daemon has direct access to the tmux socket and watches each agent worktree for
outbound handoff files. When an outbound handoff appears, the daemon validates
delivery targets, copies the handoff into each recipient inbox, sends each
recipient a generic tmux wake-up message, and moves the original outbound file
to `sent` or `failed`.

The recipient inbox is the task queue. Agents use helper scripts to accept and
complete inbox items. Queue state is represented by file location, and audit
timestamps are stored in the handoff file headers.

## Directory Layout

Each agent worktree owns this structure:

```text
.swarmforge/handoffs/
  outbox/
    tmp/
  sent/
  failed/
  inbox/
    new/
    in_process/
    completed/
```

The daemon consumes `outbox/`. Agents consume `inbox/new/` through helper
scripts. The `sent`, `failed`, `in_process`, and `completed` directories provide
the audit trail and restart state.

## Filename Format

Handoff filenames should sort by priority, timestamp, and sequence:

```text
<priority>_<timestamp>_<sequence>_from_<sender>_to_<recipient-list>.handoff
```

Example:

```text
00_20260615T140531Z_000042_from_architect_to_coder_cleaner_QA.handoff
```

Rules:

- Lower priority numbers are processed first.
- `priority` is two digits from `00` through `99`.
- `timestamp` is UTC in `YYYYMMDDTHHMMSSZ` format.
- `sequence` breaks ties for handoffs created in the same second.
- Recipients remain in the filename for audit.
- Structural filename fields are separated with underscores.
- Scripts parse authoritative metadata from file headers, not from the filename.
- Startup validation should reject role names containing underscores so recipient
  lists remain readable in audit filenames.

## Handoff File Format

Handoff files use a simple header block, a blank line, and a generated body.
Scripts may update headers, but the body is opaque after creation.

Example delivered handoff:

```text
id: 20260615T140531Z-000042
from: coder
to: cleaner
recipient: cleaner
priority: 50
type: git_handoff
role: coder
commit: a1b2c3d9
created_at: 2026-06-15T14:05:31Z
enqueued_at: 2026-06-15T14:05:32Z

Re-read your role and constitution.

merge_and_process coder a1b2c3d9
```

For broadcast handoffs, `to` preserves the full recipient list and `recipient`
identifies the specific recipient copy.

## Message Types

Agents may request only three message types.

### `awake`

Used for liveness and simple wake-up messages.

Draft:

```text
type: awake
to: two
priority: 50
```

Generated body:

```text
awake
```

The `awake` message does not include the constitution and role reminder.

### `git_handoff`

Used when a role has committed work for another role to merge and process.

Draft:

```text
type: git_handoff
to: cleaner
priority: 50
commit: a1b2c3d9e8
```

Generated body:

```text
Re-read your role and constitution.

merge_and_process coder a1b2c3d9
```

The script validates and canonicalizes the commit abbreviation before queuing
the handoff.

### `note`

Used for one short freeform message.

Draft:

```text
type: note
to: architect,QA
priority: 70
message: Waiting on QA result before merging cleanup branch.
```

Generated body:

```text
Re-read your role and constitution.

Waiting on QA result before merging cleanup branch.
```

The `message` value must be a single line no longer than 80 characters.

## `swarm_handoff.sh`

`swarm_handoff.sh` should be the strict outbound protocol gate.

Proposed usage:

```sh
swarm_handoff.sh ./tmp/handoff.txt
```

Responsibilities:

- Read a draft handoff file.
- Validate all fields and emit detailed repair guidance for malformed drafts.
- Reject reserved headers supplied by agents.
- Infer `from` from the current agent/worktree.
- Validate `to` against configured agents.
- Generate `id`, `created_at`, filename timestamp, and sequence.
- Validate `priority` as `00` through `99`.
- Validate `type` as `awake`, `git_handoff`, or `note`.
- Validate `git_handoff` commits as real, unambiguous commits.
- Canonicalize valid commit abbreviations.
- Generate `role` from the current sender role for `git_handoff`.
- Generate the canonical body.
- Atomically install the completed file into `outbox/`.

Atomic outbound write sequence:

1. Write the generated handoff to `outbox/tmp/<filename>.tmp`.
2. Flush and close the file.
3. Rename it to `outbox/<filename>.handoff`.

The daemon should ignore `outbox/tmp/` and process only final `.handoff` files
that appear directly under `outbox/`.

Reserved headers:

```text
id
from
role
recipient
created_at
enqueued_at
dequeued_at
completed_at
```

Validation errors should be explicit enough for an agent to repair the draft.

Example error:

```text
HANDOFF INVALID: ./tmp/handoff.txt

Errors:
- Line 3: `priority` must be two digits from 00 to 99; got `urgent`.
- Header `completed_at` is reserved and must not be written by agents.
- message: commit `a1b2c3` is ambiguous; use at least 9 characters.

Expected git_handoff format:

type: git_handoff
to: cleaner
priority: 50
commit: <commit-abbrev>
```

## Commit Validation

For `git_handoff`, `swarm_handoff.sh` should validate the commit abbreviation
with Git.

Rules:

- The commit abbreviation must be hexadecimal.
- It must be exactly 10 characters.
- It must resolve to exactly one object.
- The resolved object must be a commit.
- The script should write a canonical abbreviation into the queued handoff.

This prevents agents from sending corrupted or ambiguous SHA abbreviations.

## Handoff Daemon

The daemon should be implemented in Babashka.

Rationale:

- The service is mostly filesystem traversal, parsing, sorting, renaming, and
  subprocess calls.
- Babashka keeps the implementation small and easier to change while the
  protocol is still evolving.

Responsibilities:

- Discover configured agents and worktrees.
- Poll each agent `outbox/`.
- Process only complete `.handoff` files, never files in `outbox/tmp/`.
- Copy each handoff to every recipient `inbox/new/`.
- Add `recipient` and `enqueued_at` to each recipient copy.
- Send a generic tmux wake-up message to each recipient.
- Move the original outbox file to `sent/` after successful delivery.
- Move malformed or undeliverable files to `failed/` with useful diagnostics.
- Avoid duplicate delivery when retrying after interruption.

The tmux message should not name the delivered file. It should avoid biasing the
recipient toward one file and should force queue-order processing.

Example tmux wake-up:

```text
You have new handoff mail. If you are not currently working on a task, run ready_for_next_task.sh.
```

## Queue Helper Scripts

Agents should not manually move inbox files. Helper scripts should own queue
state transitions.

### `ready_for_next_task.sh`

Responsibilities:

- Run inside one agent worktree.
- Check `inbox/in_process/` first.
- If an in-process file exists, report that it must be resumed or completed
  before accepting new work.
- If no in-process file exists, select the first file in `inbox/new/` by sorted
  filename order.
- Atomically move that file to `inbox/in_process/`.
- Add or update `dequeued_at`.
- Print the accepted task path, sender, message type, priority, and payload.
- Print `NO_TASK` if no inbox item is available.
- Refuse ambiguous states, such as multiple in-process files, unless an explicit
  repair is made outside the helper.

Example success:

```text
TASK: .swarmforge/handoffs/inbox/in_process/00_20260615T140531Z_000042_from_architect_to_coder.handoff
FROM: architect
TYPE: git_handoff
PRIORITY: 00
PAYLOAD:
Re-read your role and constitution.

merge_and_process architect a1b2c3d9
```

### `done_with_current_task.sh`

Responsibilities:

- Run inside one agent worktree.
- Require exactly one file in `inbox/in_process/`.
- Add or update `completed_at`.
- Move the file to `inbox/completed/`.
- Print the completed task path.
- Call `ready_for_next_task.sh` after completion and pass through its output.
- Refuse to run if there are zero or multiple in-process files, unless an
  explicit repair is made outside the helper.

`done_with_current_task.sh` should not duplicate queue-selection logic.
`ready_for_next_task.sh` should remain the single owner of checking
`inbox/in_process/`, selecting the next sorted `inbox/new/` item, moving it to
`inbox/in_process/`, adding `dequeued_at`, and printing `TASK` or `NO_TASK`.

Example success:

```text
COMPLETED: .swarmforge/handoffs/inbox/completed/00_20260615T140531Z_000042_from_architect_to_coder.handoff
TASK: .swarmforge/handoffs/inbox/in_process/50_20260615T140600Z_000043_from_cleaner_to_coder.handoff
FROM: cleaner
TYPE: note
PRIORITY: 50
PAYLOAD:
Re-read your role and constitution.

Waiting on QA result before merging cleanup branch.
```

Example success with no queued follow-up:

```text
COMPLETED: .swarmforge/handoffs/inbox/completed/00_20260615T140531Z_000042_from_architect_to_coder.handoff
NO_TASK
```

## Agent Queue Rules

Prompts should instruct agents to follow this loop:

1. When notified, run `ready_for_next_task.sh`.
2. If it prints `NO_TASK`, stop waiting for work.
3. If it prints `TASK: <path>`, treat the printed `PAYLOAD` as the task.
4. Use only the task information printed by the helper scripts.
5. If a tmux wake-up arrives while already working on a task, ignore it.
6. When the task is fully complete, run `done_with_current_task.sh`.
7. If `done_with_current_task.sh` prints `TASK: <path>`, treat the printed
   `PAYLOAD` as the next task.
8. If `done_with_current_task.sh` prints `NO_TASK`, stop waiting for work.

On restart, an agent should run `ready_for_next_task.sh` and follow its output.

Tmux wake-ups are intentionally lossy. They only prompt an idle agent to check
its durable inbox. A busy agent can ignore them because task completion also
checks the queue and accepts the next task in priority order.

## Audit Trail

The file system state and handoff headers replace the logbook.

Important headers:

```text
id
from
to
recipient
priority
type
created_at
enqueued_at
dequeued_at
completed_at
```

Lifecycle ownership:

- `swarm_handoff.sh` writes `id`, `from`, `to`, `priority`, `type`, and
  `created_at`.
- `handoffd` writes `recipient` and `enqueued_at` into each recipient copy.
- `ready_for_next_task.sh` writes `dequeued_at`.
- `done_with_current_task.sh` writes `completed_at`.

## Daemon Shutdown

The swarm launcher should own the daemon lifecycle.

Startup:

- Start the daemon after creating or discovering the tmux session.
- Write daemon runtime files under `.swarmforge/daemon/`.

Runtime files:

```text
.swarmforge/daemon/
  handoffd.pid
  handoffd.log
  stop
```

Shutdown:

- When the swarm is torn down, the launcher sends `TERM` to the daemon.
- The daemon traps `TERM`, finishes any current delivery transaction, removes
  its PID file, logs shutdown, and exits.
- The daemon may also watch `.swarmforge/daemon/stop` as a secondary shutdown
  mechanism.

Delivery should be transaction-like:

1. Detect an outbox file.
2. Copy it to all recipient inboxes.
3. Send wake-up notifications.
4. Move the original outbox file to `sent/`.
5. If interrupted before completion, retry without duplicating already delivered
   recipient copies.

## Expected Prompt And Script Changes

This proposal eliminates the current send/receive/complete command variations.

Expected changes:

- Replace `send-handoff.sh`, `receive-handoff.sh`, `complete-handoff.sh`, and
  related request-file flows with:
  - `swarm_handoff.sh`
  - `ready_for_next_task.sh`
  - `done_with_current_task.sh`
  - `handoffd`
- Update role and constitution prompts to describe the new inbox workflow.
- Remove prompt instructions that require agents to send tmux notifications.
- Remove prompt instructions that require agents to write long handoff bodies.
- Remove logbook requirements once the file audit trail is in place.

## Finalized Decisions

- The handoff daemon is written in Babashka.
- Git handoff commit abbreviations are exactly 10 hexadecimal characters.
- `note` handoffs have no optional classification field.
- Helper scripts do not provide recovery modes for ambiguous queue state.
- The daemon does not perform a second full validation pass on outbox files;
  `swarm_handoff.sh` is the validation boundary.
