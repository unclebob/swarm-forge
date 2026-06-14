---
status: accepted
---

# retro-triage: operator root-cause diagnosis, distinct from the curator

The fork keeps a `retro-triage` skill: an operator-invoked tool that turns a *batch* of session retros into a validated, cross-session **root-cause diagnosis** from which a human files issues. It lives in `.claude/skills/` (an operator tool), not `swarmforge/skills/` (the skills the swarm's own roles run).

**Why it exists alongside the curator.** The curator (ADR 0013) already consumes session retros — but autonomously, one item at a time, to promote agent-facing knowledge into the repo. retro-triage is its complement, not a duplicate. The curator fixes *"the swarm doesn't **know** X"* — a missing rule becomes repo knowledge. retro-triage fixes *"the swarm is **structurally doing** X wrong"* — a pipeline, tooling, or strategy defect becomes a filed issue for a human to act on. The structural causes it hunts (one upstream decision surfacing as different pains across five roles) are precisely what a per-item consumer like the curator cannot see, because they live *across* retros and below any single retro's notice.

**Diagnosis is the product, not sorting.** The skill exists to prevent two failure modes that occurred in real runs: codifying a workaround as a win (a slick technique that only exists to cope with a self-inflicted problem is evidence of cost, not a pattern), and inheriting a retro's own proposed fix (the retro reports a symptom; its suggested fix is a hypothesis, not a finding). Every root cause is recorded with re-pullable receipts — a transcript quote by session id, git output, a `file:line` — and validated against the artifacts; an unvalidated cause is not a finding and cannot be filed.

**Why `.claude/skills/`, not `swarmforge/skills/`.** It is a human's meta-analysis tool, not a step any swarm role executes. Keeping it with the operator skills leaves the swarm's own skill set to the things the swarm itself runs (`agent-retro`, `setup-swarm`).

**Sharing the retro pool without starvation.** Both the curator and retro-triage read `~/.claude/worklog/retros/`. They must not consume each other's unseen retros: the curator processes and archives retros to `processed/` each run (ADR 0013), and retro-triage reads the full history — live pool plus archive — while tracking its own consolidation independently of the curator's mark. Neither destroys what the other has not yet seen.

## Pending implementation

- `main`: restore `.claude/skills/retro-triage/SKILL.md` as-is (byte-identical across branches). Source: `feat/issue-20-a-retro-skill-upgrade`.
- Make retro-triage's retro detector glob the curator's `processed/` archive in addition to the live `~/.claude/worklog/retros/` directory, so curated retros remain visible to a later diagnosis.
