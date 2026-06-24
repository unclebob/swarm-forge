---
name: retro-triage
description: Use when unprocessed session retros sit in ~/.claude/worklog/retros/ and a batch needs root-cause analysis across the sessions. Triggers on "triage the retros", "consolidate retros", "what did we learn this batch", "file issues from the last sessions", "any new pains from the swarm runs".
---

# retro-triage

## Overview

Turn a batch of session retros into a **validated root-cause diagnosis** with framed candidate actionables — written to a consolidation doc. The pipeline is **harvest → reconstruct → diagnose → validate → frame**. You do NOT bucket-and-file: bucketing per-signal scatters root causes and reproduces the retros' own framing. Diagnosis is the product; a human files issues from it.

This is NOT `mattpocock-skills:triage`. That skill triages *incoming* issues from a human reporter through a grilling/labeling state machine. Here there is no reporter — the source is a pile of dead session transcripts, and the output is a diagnosis that must carry its own evidence. Do not hand off to that skill.

**Core principle: the retro is a symptom report, not a diagnosis. Your job is the diagnosis.** A retro reliably tells you *what hurt* in one role's one session. Its `## What Didn't Work`/`## Actions`/`## What Worked` are the author's framing under a keyhole view — hints, never findings. **The root cause almost never lives inside a single retro.** It lives *across* retros (one upstream decision surfaces as different pains in five roles) and *below* their notice (routine work nobody thought to complain about). If your actionables look like the retros' proposed fixes with an "unverified" label, you have sorted, not diagnosed — and you are wrong.

**Two failure modes this skill exists to prevent (both happened in production):**
- **Codifying a workaround as a win.** A slick conflict-resolution technique landed in `## What Worked` → got filed as a "pattern worth codifying." It was a workaround for self-inflicted merge conflicts caused by the squash-merge strategy. The real finding was the upstream cause, invisible because nobody's retro said "the strategy made me merge."
- **Inheriting the retro's fix.** A prior batch filed the retros' proposed "push before handoff" rule. The mechanism was wrong (the workflow already merged by hash; the role *had* pushed). Every issue was closed as mis-framed.

## When to Use

- Unprocessed retros sit in `~/.claude/worklog/retros/` (no `consolidated:` frontmatter) — the curator's `processed/` archive is also scanned, so curated retros stay visible to a later diagnosis
- Periodically after a swarmforge batch closes

**Do NOT use:**
- For a single retro — too little signal. Read it directly.
- To re-process already-stamped retros — skip them.
- To triage incoming human-reported issues — that is `mattpocock-skills:triage`.

## Inputs

1. **Unprocessed retros** — files in `~/.claude/worklog/retros/*.md` lacking `consolidated:` in their first 5 lines AND whose name lacks `CONSOLIDATED`. Use this detector exactly (`grep -L` over the whole file gives false positives when "consolidated" appears in the body):
   ```bash
   for f in ~/.claude/worklog/retros/*.md ~/.claude/worklog/retros/processed/*.md; do
     [ -e "$f" ] || continue   # globs that match nothing expand literally
     case "$(basename "$f")" in *CONSOLIDATED*) continue ;; esac
     head -5 "$f" | grep -q '^consolidated:' || echo "$f"
   done
   ```
2. **Prior consolidations** — any `*-CONSOLIDATED-actionables.md` in the same dir.
3. **Open issues** — `gh issue list --state open --limit 50 --json number,title,labels`.
4. **Project gotchas** — only the `## Gotchas` section of the repo's `AGENTS.md`. Read other repo files (role prompts, constitution, scripts) only when a signal explicitly references them for diagnosis.
5. **Closed issues (on-demand)** — only when a signal smells pre-decided. Dispatch a Haiku subagent with `gh issue list --state closed --search "<keywords>"`; do not pull closed-issue text into main context.

Context discipline for the bulk read is defined in Phase 1 (one subagent, never split).

## Phase 1 — Harvest (raw symptoms only, no conclusions)

Extract verbatim. These sections are **the author's framing — input to diagnosis, never output.** Do not let a section's label decide an actionable's fate.

| Section | What it actually is |
|---|---|
| `## What Didn't Work` | Symptoms + the author's guessed cause. Keep the symptom verbatim; discard the guess until you re-derive it. |
| `## Actions` | The author's *proposed fix*. A hypothesis. Never file it as-is. |
| `## What Worked` | What the author was *proud of*. **Trap:** a thing done well may be a workaround for an upstream problem. Run the workaround test (Phase 3) before believing it's a pattern. |
| `## Tool Result Waste` | Efficiency symptoms. Usually a symptom of something, not a finding itself. |

Each retro header carries **Session ID**, **Branch**, **Date**, and references **commit SHAs / PRs**. Capture the commits — they are the traceability anchor (see below).

Do NOT skip token/cost tables: a cluster of "expensive session" lines is often the visible edge of an invisible-work root cause (Phase 3).

**Context discipline:** delegate the bulk read to ONE subagent (not several split by file — root causes cross the split line, and a half-batch reader can't see them). It returns per-file verbatim signals; you do the cross-retro work in the main thread.

## Phase 2 — Reconstruct the episode (independent of the retros)

Before any classification, rebuild what the **system actually did** this batch, from durable artifacts, NOT from what the retros say happened:

- `git log --oneline --graph` over the batch range; note the branch topology and **how branches landed** (squash? merge commit? rebase?). Squash-to-main + long-lived role branches *mechanically* generate divergence — a root cause no single retro will name.
- Which gates ran and which didn't (`verify` chain, mutation, reality-check, arch-check). Read the actual scripts/config when a symptom touches them.
- What landed where (PRs, the final commits on `main`).

Write a 3–6 line factual reconstruction. This is the lens you cluster symptoms onto.

## Phase 3 — Diagnose (derive cause across symptoms, then validate)

Cluster the harvested symptoms onto the reconstruction and ask: **what one decision or missing mechanism explains this cluster?** Two mandatory probes, because the retros are blind to both:

1. **Workaround-vs-win.** For every `## What Worked` item and every "we handled it well": *would this work have been necessary if something upstream were right?* If the heroics exist to cope with a self-inflicted problem, the finding is the upstream cause — the technique is evidence of cost, not a pattern to codify.
2. **Invisible / normalized work.** Scan for work every role did routinely and nobody flagged as pain — repeated merges, branch resets, re-runs, "expensive session" cost lines. Normalized cost is where the biggest root causes hide, precisely because no retro complains about it.

Then **validate each candidate cause against the artifacts before it becomes an actionable.** Read the prompt/script/config the cause implicates. Kill the ones the evidence contradicts (the "push before handoff" fix died here: `workflow.prompt` already merged by hash). An unvalidated cause is not a finding — it is the retro's guess wearing a label.

**The session transcript IS an artifact — and the retro can be wrong about its own session.** Every retro records a Session ID; that is the durable handle to ground truth. Before quoting any *figure, sequence, or "the user said X"* from a retro, confirm it in the actual transcript (resolve via the `entire` CLI by session id). Retros mis-state: a real batch retro reported "70.41%, 22 survivors" for one file when the number belonged to a *different* file and that session had run no mutation at all. **Delegate this check to ONE subagent** (give it all the session IDs + the claims; one reader so findings stay coherent — never split across agents). Quote what the transcript actually shows, not what the retro says it shows.

## The root-cause record — the unit of output, and the evidence gate

**This skill's own thesis is "prose rules get skipped; prefer a mechanical gate." Apply it to yourself.** "Validate the cause" is prose an agent can claim without doing (this skill was used to write `Validated:` with no artifact read, and to quote a mutation figure that belonged to a different file). The gate is: **every root cause is recorded in this exact shape, and the Evidence block must contain literal receipts. A receipt is a fact someone else could re-pull, not your summary of it.** No receipt → the verdict is `INSUFFICIENT` by default → it is NOT a finding, cannot become an actionable, cannot be filed.

```markdown
### RC-N — <one-line cause, stated as a claim that can be falsified>
- **Symptoms it explains:** verbatim quotes + which retros/sessions (the cross-retro cluster)
- **Probes:** workaround-vs-win → <result>; invisible/normalized-work → <result>
- **Evidence (receipts — each line re-pullable by someone else):**
  - `file:line` quoted, OR `command` + its ACTUAL output, OR transcript quote `@<session-id>`
  - …one line per artifact checked; state what each proves or CONTRADICTS
- **Verdict:** SUPPORTED | WEAKENED | INSUFFICIENT — and it must follow from the receipts above, not from the retro's say-so
- **Disposition + framing:** <bucket tag, below> → if SUPPORTED, the framed candidate (investigate/decide, target file, default `ready-for-human`)
```

Hard rules on the record:
- **A bare `Validated:` / "confirmed" with no receipt line is a forgery.** Treat it as INSUFFICIENT.
- **The retro's own numbers, sequences, and "the user said X" are claims, not receipts.** A receipt is the transcript quote (by session id), the git output, the `file:line`. If your only source is the retro, your verdict is at best INSUFFICIENT.
- **A WEAKENED/INSUFFICIENT cause can still be a real finding** — file it as needs-info with the gap stated. What you may NOT do is upgrade it to a prescribed fix.

The buckets below are a **disposition tag on a finished record**, not bins you sort raw signals into.

## Disposition tags (applied to a validated record — never to raw signals)

| # | Tag | Test |
|---|---|---|
| 1 | **Failed-to-learn** | Pain recurs AND a prior CONSOLIDATED row or AGENTS.md `## Gotchas` row documented a **specific fix**. Recurs without a prior remediation → Bucket 3. **Always emit the header** (write "None this batch — verified against [N] prior fixes."). |
| 2 | **Dupe-of-existing-issue** | Matches an open issue. Output: link + a clarification comment (self-contained, see below). |
| 3 | **New-actionable, issue-shaped** | New pain worth tracking. File a self-contained, traceable issue (see Authoring contract). Default `ready-for-human`. |
| 4 | **New-actionable, spec-shaped** | Fix needs design before a ticket (new protocol, a system port). State the design question in the issue; still file as `ready-for-human`. |
| 5 | **Needs-info / decision** | Mechanism contested or unclear. `ready-for-human`. |
| 6 | **Pattern-worth-codifying** | A genuine technique worth a rule/template — ONLY after it passes the workaround-vs-win probe (Phase 3). If the "win" exists to cope with an upstream problem, it is NOT a pattern; route the upstream cause to Bucket 3/5 and cite the technique as evidence of cost. |
| 7 | **Already-learned / dropped** | Pain matches a documented rule, the session **followed it**, retro just confirms it worked. One-line note + source. |
| 8 | **Noise** | Not documented anywhere AND "structural to async swarm" / "generic unfixable friction" / "one-off not worth a rule". Explicit rationale per item. Tiebreak vs 7: written rule exists → 7; no rule and no mechanism → 8. |

## Issue authoring contract (used only at the human-gated filing step)

A filed issue is a SUPPORTED root-cause record, rewritten to be **self-contained** and **traceable**: a future agent must extract a valid learning from the body *alone*, without reloading any transcript or local file. The record's receipts become the issue's evidence; the record's verdict sets the issue's confidence.

**Two hard rules, both learned from real failures:**

1. **No reference to anything local.** Never cite a retro filename, a `~/.claude/worklog/...` path, a consolidation doc, or a session-transcript `.jsonl` path. Those live on one machine and die elsewhere. Cite only repo paths (`swarmforge/...`, `api/src/...`, `AGENTS.md`) and durable handles (commit SHAs, PR/issue numbers).
2. **Preserve the signal verbatim — do not paraphrase it away.** Your prose summary is not the evidence. Quote the exact `## What Didn't Work` / `## Actions` lines, error strings, commands, and user corrections. Paraphrase loses the debuggable signal.

**Traceability anchor = the git commit (the `explain` skill pattern).** The durable link from an issue to its origin is the **commit SHA / PR** where the work or pain landed — it is on `origin`, shared and permanent. Provide the resolver line literally:
```
entire checkpoint explain --commit <sha>
```
The Session UUID is a *secondary, local-only* hint — label it as such; never make it the primary anchor. When a pain never landed as its own commit (halted session, local-only WIP), say so explicitly rather than inventing an anchor.

**Body schema** (adapted from the `extracting-skill-learnings` skill):
```markdown
> *This was generated by AI during triage.*

---
date: YYYY-MM-DD
model: <e.g. claude-opus-4-7>
harness: <e.g. claude-code>
source: multi-agent (swarmforge) session retrospectives, <batch date>
---

Pain is stated as fact. The cause carries the record's verdict (SUPPORTED / WEAKENED / INSUFFICIENT) — never assert more confidence than the receipts earned.

## Raw signals
(verbatim quotes, attributed to role + Session ID; redact secrets/PII as [redacted], keep branch names / SHAs / paths)

## Defect
**What happened:** ...
**Cause (verdict: SUPPORTED|WEAKENED|INSUFFICIENT):** ...
**Evidence:** the record's receipts — `file:line`, command+output, transcript quote. (NOT the retro's summary.)
**Attribution:** skill workflow | model reasoning | harness enforcement

## Actionables
| # | Actionable | Target | Confidence | Status |
|---|-----------|--------|-----------|--------|
| 1 | investigate/decide (prescribed fix ONLY if verdict=SUPPORTED on a mechanism you validated) | <repo path> | from verdict | pending |

## Traceability
- Landing commit(s) / PR(s): <sha or #PR> — resolve via `entire checkpoint explain --commit <sha>`
- Session UUID(s) (local transcript only, not durable): <uuid> (<role>)
```

## Judgment rules

- **Pain = fact, cause = hypothesis.** Never promote a retro's guessed cause to asserted root cause. Default new actionables to `ready-for-human`; reserve a prescribed fix (and `ready-for-agent`) for a mechanism you independently verified, not merely a plausible one.
- **Don't over-fragment one cause into many fixes.** Several signals often trace to one gap seen from different roles. Collapse into one broad problem statement rather than N prescriptive tickets — especially since each per-signal "fix" is only a hypothesis. Tiebreak for keeping separate: the fixes would be genuinely different commits/PRs.
- **Prefer a lint/CI gate over a prompt/doc edit** when the rule is mechanically checkable — that is what makes rules stick (prose rules get skipped). Note this in Actionables.
- **Source attribution** belongs in YOUR scratch reasoning only (`Source: <retro-name> (primary), <retro-name> (secondary)`). It must NOT appear in issue bodies (authoring rule 1).
- **Conflict rule:** two retros propose different mechanisms for one pain → list both as options, `ready-for-human`. Do not silently pick.
- **Stale-status verification:** for every `[x] done` row in the most recent prior CONSOLIDATED, grep git log / target file for evidence. Flag absences.
- **Governing insight:** include a section only if one meta-pattern explains ≥3 pains. Frame it as a candidate explanation ("these pains *may* share…"), not a proven diagnosis. Forced coherence is fabrication.

## Output: the consolidation doc (you do NOT file issues)

Write the diagnosis to `~/.claude/worklog/retros/<batch-date>-CONSOLIDATED-actionables.md`: the Phase-2 reconstruction, the validated root causes, and the bucketed candidates. Filing GitHub issues is a **separate, human-gated step** — present the doc and ask before creating anything. (Both prior auto-filed batches were closed as mis-framed; the validation step is necessary but not yet proven sufficient.)

When the human approves filing, follow the authoring contract per Bucket-3/Bucket-5 candidate:
- `gh issue create --label "<category>,<state>" --body-file <tmp>` — category `enhancement`/`bug`; state defaults to `ready-for-human`. Reserve `ready-for-agent` for a mechanism you validated in Phase 3, never a plausible one.
- Bucket 2: post the clarification comment with `gh issue comment`; ensure category+state labels are present.
- Verify every touched issue ends with exactly one category label and one state label.
- Close any issue whose feature has demonstrably landed (cite the merged PR/commit).

## Post-step: stamp source retros

After the doc is written, prepend each source retro (every one included, even Bucket 7/8) with:
```yaml
---
consolidated: YYYY-MM-DD
---
```
Without the stamp the detector re-processes them next run. For retros consolidated by a prior pre-existing doc, stamp with that doc's date, not today's.

## Common mistakes

- **Paraphrasing the signal instead of quoting it.** Your summary is not the evidence. Quote verbatim.
- **Anchoring on the session UUID or a retro filename.** Both are local-only. Anchor on the commit SHA; UUID is a secondary hint.
- **Stating the retro's guessed cause as fact.** Pain is fact; cause is hypothesis. Label it.
- **Bucketing raw signals instead of validated causes.** The buckets format Phase-3 output. Classifying raw symptoms straight into buckets is the sort-not-diagnose failure.
- **Codifying a workaround as a pattern.** Run the workaround-vs-win probe on every `## What Worked` item first.
- **Skipping the Phase-2 reconstruction.** Without rebuilding what the system did from git/config, cross-retro and invisible-work causes stay invisible.
- **Filing N tickets for one underlying gap.** Collapse to one broad problem statement.
- **Loading all retros into main context, or splitting the read across subagents.** One reader, verbatim; diagnose in the main thread.

## Red flags — STOP

| Thought | Reality |
|---|---|
| "I've bucketed the signals, now I'll file." | You sorted, you didn't diagnose. Do Phase 2–3 first; buckets format *validated causes*. |
| "This went in `## What Worked`, so it's a pattern to codify." | Maybe it's a workaround for an upstream problem. Run the workaround-vs-win probe. |
| "The retro's proposed fix sounds right, I'll file it." | That's the author's guess. Re-derive and validate against the artifact, or you ship a closed-as-mis-framed issue. |
| "Every retro mentions merge pain — five separate findings." | Probably ONE upstream cause (e.g. squash divergence) seen five ways. Reconstruct, then collapse. |
| "No retro complains about it, so it's fine." | Normalized/invisible work hides the biggest causes. Probe for it explicitly. |
| "The cause is obvious / ready-for-agent." | Unvalidated = the retro's guess with a label. Validate against the artifact; default `ready-for-human`. |
| "I'll write `Validated:` / `confirmed` here." | Not without a receipt on the next line. A verdict with no `file:line` / command-output / transcript quote is a forgery → INSUFFICIENT. |
| "The retro says 70.41%, I'll quote that." | The retro's number is a claim, not a receipt. Pull it from the transcript by session id first — it may belong to a different file. |
| "I'll summarize the pain in my own words." | Paraphrase loses the signal. Quote verbatim. |
