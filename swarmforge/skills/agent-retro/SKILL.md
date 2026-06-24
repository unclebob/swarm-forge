---
name: agent-retro
description: Run a conversation retrospective — analyze what happened in this session, what worked, what didn't, and propose concrete improvements. Use when the user says "retro", "retrospective", "what happened in this session", "session review", "what did we do", "analyze this conversation", or when wrapping up a long session. Especially useful after using a skill you're developing. In swarmforge: invoked automatically as the last step before each role goes idle.
compatibility: Primary — requires `entire` CLI (0.6.2+) for transcript extraction. Fallback — Claude Code ~/.claude/projects/ path. Python 3.8+ for the extraction script.
metadata:
  author: gabadi/swarm-forge (fork of giannimassi/agent-retro)
  version: "0.1.0"
---

# agent-retro

## Step 1 — Extract Session Data

**Primary path (entire):**
1. Run `entire session current --json` to get the active session ID and worktree path.
2. If a session ID is returned:
   - Run `entire session info <id> --transcript > /tmp/retro-session.jsonl`
   - Verify: `python3 ${CLAUDE_SKILL_DIR}/scripts/extract.py /tmp/retro-session.jsonl --metadata-only`
   - If verification succeeds, run full extraction: `python3 ${CLAUDE_SKILL_DIR}/scripts/extract.py /tmp/retro-session.jsonl --summary > /tmp/retro-extract.json`
   - Proceed to Step 2 with `/tmp/retro-extract.json`.

**Fallback path (Claude Code only):**
If `entire` is not installed or `entire session current` returns no session:
1. Look for session pid files in `~/.claude/sessions/*.json`. Read each, match `cwd` to `$PWD`. Take the most recently modified matching entry.
2. If found: use the `sessionId` to find the transcript in `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl`.
3. If not found via pid: take the most recently modified `.jsonl` in `~/.claude/projects/<encoded-cwd>/`.
4. Verify: `python3 ${CLAUDE_SKILL_DIR}/scripts/extract.py <path> --metadata-only`
5. Run full extraction: `python3 ${CLAUDE_SKILL_DIR}/scripts/extract.py <path> --summary > /tmp/retro-extract.json`

**If no transcript is found:** Report "No session transcript found" and stop. Do not fabricate data.

Raw JSONL is 1MB+ per session — never stream transcript bytes inline into context. Always write to a temp file and pass the path to extract.py.

---

## Step 2 — Read the Conversation Arc

Read `conversation_arc` from `/tmp/retro-extract.json`. This is the full story of the session: every user message and assistant response in order.

Identify:
- User corrections ("no, not that", "stop", "undo", "wrong")
- Redirects (user changing direction mid-task)
- Repeated instructions (same request given more than once)
- Pivots (abandoned approaches)
- Friction moments (back-and-forth on a single point)

---

## Step 3 — Classify Outcomes

Classify what the session produced:
- New code / feature
- Bug fix
- Communication (messages, comments, docs)
- Setup / configuration changes
- Spec or design artifact
- Process improvement
- Review or analysis
- Research
- Skill development

A session may have multiple outcomes.

---

## Step 4 — Analyze What Worked

Identify:
- First-try successes (task completed without corrections)
- Efficient delegation (agents dispatched with clear scope)
- Good skill matches (right skill for the task)
- Clean conversation flow (no redirects)
- Smart tool choice (right tool, right scope)

---

## Step 5 — Analyze What Didn't Work

Identify friction patterns:
- User corrections, redirects, repetitions, stops, frustration signals
- Wasted agent dispatches (dispatched but result unused)
- Oversized tool results (large reads never referenced)
- Tool call retries (same tool called multiple times for the same target)
- Abandoned approaches (started, then discarded)
- Over-engineering (more than the task required)
- Under-specification (task started with insufficient context)

For skill-development retros: read the active SKILL.md (`${CLAUDE_SKILL_DIR}/SKILL.md` of the skill being developed) and identify which instruction caused each friction.

Read `tool_result_sizes` from the extract — flag any tool result over 50KB that was followed by no further reference to that file.

---

## Step 6 — Propose Actions

Lead with the defense-first question: **"What defensive rule did this session's work absorb that future maintainers must keep intact?"** Answer it before cataloging friction — rule-shaped learnings surface before cause-shaped ones.

Capture-first guard: enumerate every candidate learning from Steps 4–5 in full before writing anything to the retro file. Do not filter for "obviousness" or "self-correcting" here — capture everything; the curation stage downstream owns discards.

For each friction pattern, propose one of these action types:
- `skill-update` — change an existing skill. Include before/after text.
- `skill-create` — create a new skill.
- `rule-update` — change a rule or instruction in CLAUDE.md or a role prompt.
- `rule-create` — create a new rule.
- `setup-change` — change a configuration or environment setting.
- `memory-update` — update or create a memory entry.
- `investigate` — flag something for human review (uncertain root cause).
- `acknowledge` — nothing to change; note what worked well.

Be specific. "Improve X" is not a proposal. "Change the wording in Step 3 from Y to Z" is a proposal.

**Scope** — tag every proposed action with exactly one scope value:
- `project` — knowledge about the target project (its code, config, tools, conventions).
- `swarmforge` — knowledge about the harness itself (role prompts, constitution, scripts, pipeline mechanics).
- `skill` — a reusable procedure that should become or amend a skill.
- `ephemeral` — true one-offs; recorded for audit, never promoted.

---

## Step 7 — Write the Retro File

Write to `~/.claude/worklog/retros/YYYY-MM-DD-<slug>.md` where `<slug>` is a 3–5 word kebab-case summary of the session.

Structure:
```markdown
# Session Retro: <slug>
Date: YYYY-MM-DD
Session ID: <id>
Role: <swarmforge role name, or "interactive" outside a swarm>
Branch: <branch>
Duration: <N>m
Cost: $<N>

## Token Budget
| Category | Tokens | Cost |
|---|---|---|
| Input | N | $N |
| Output | N | $N |
| Cache create | N | $N |
| Cache read | N | $N |
| **Total** | **N** | **$N** |

## Tool Result Waste
<table of oversized unused tool results, or "None detected">

## What Worked
<bullet list>

## What Didn't Work
<bullet list with root cause per item>

## Actions
| # | Type | Scope | Description | Target |
|---|------|-------|-------------|--------|
| 1 | skill-update | project | ... | ... |
```

---

## Step 8 — Walk Through Actions

Determine the mode:

**Interactive session (a human is present):**
- Present the retro file path and summary counts (N worked, N didn't work, N actions).
- Walk through each proposed action one by one: show type, scope, description, target. Ask: "Apply? [y/n/defer]". Apply approved actions immediately; mark deferred/skipped in the table.
- After the walkthrough, show the final action table with statuses.

**Autonomous session (swarmforge role, no human in the loop):**
- Do not ask anything. Do not apply any action.
- Mark every action's status as `pending-curation` in the table and finish the retro file.
- The curator role consumes the file downstream; your only job is complete, well-tagged capture.

---

## Step 9 — Preemptive Handoff Recommendation

Check `session` metadata from the extract:
- If `turn_count` > 500, `duration_seconds` > 14400 (4h), or `estimated_cost_usd` > 300:
  - Add a `investigate` action: "Session size threshold reached — consider handoff"
  - Include two ready-to-paste prompts:
    - For `/compact`: "Continue from: <brief state summary>"
    - For `/clear`: "Resume from: <brief state summary> — key context: <3 bullet points>"
