# Fork Divergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-apply every documented SwarmForge fork divergence (ADRs 0001–0021 + manifest rows) on top of pristine `upstream`, as **two pull requests — one per delivery branch**: one PR on `main` (scripts + skills), one PR on `six-pack` (prompts + constitution + conf + root swarm). Each PR is the minimal additive diff vs upstream, built from ordered, per-divergence commits.

**Architecture:** Two delivery branches. `main` carries scripts + skills + docs/ADRs; `six-pack` carries role prompts, constitution articles, templates, the fidelity manifest, `swarmforge.conf`, and the root `swarm` bootstrap. Every branch is kept identical to its `upstream/<branch>` and advanced by **merge**, never rebase (ADR 0001). **four-pack is frozen** — no fork content is ever applied to it (manifest decision 2026-06-14); it stays a pure merge-mirror of `upstream/four-pack`.

**Tech Stack:** zsh (`swarmforge.sh` and the handoff scripts run under zsh — note `${=var}` word-splitting, `typeset -a/-A`, `${var:h}`/`${var:t}` modifiers), Python 3 (settings.local.json read-modify-write), Markdown skills (`SKILL.md`), `*.prompt` plain-text role/constitution files, Gherkin `.feature` templates, `gh` CLI.

---

## Conventions (read before any task)

**Two PRs, two branches.** Exactly one branch and one PR per delivery branch:
- **PR 1 (MAIN)** — branch `feat/fork-divergences-main` off `origin/main`; all of the MAIN TRACK commits below; PR opened `--base main`.
- **PR 2 (SIX-PACK)** — branch `feat/fork-divergences-six-pack` off `origin/six-pack`; all of the SIX-PACK TRACK commits below; PR opened `--base six-pack`.

There is **no four-pack PR** (frozen). The two PRs are independent of each other and can proceed in parallel.

**Commits.** Each divergence is one commit on its track branch, applied in the listed order (the order encodes the within-branch dependencies — e.g. the bundle commit precedes the knowledge-injection commit that extends it). One commit per divergence keeps the single PR reviewable and tailored. Do **not** create extra branches or PRs.

**Baseline anchor.** The fork layer is re-applied onto a recorded pristine-upstream baseline (ADR 0001): `main` @ `d947f67` (tag `fork-base/2026-06-14-main`) and `six-pack` @ `cbd1697` (tag `fork-base/2026-06-14-six-pack`). As of 2026-06-14 `origin/main`/`origin/six-pack` equal these exactly, so branching off `origin/<branch>` == branching off the tag. The two implementation branches come off the real delivery branches, **not** off this docs branch. If `origin` has since advanced, branch off the tag instead so the diff stays measured against the recorded baseline.

**Merge style.** Fork divergences are **squash-merged** (ADR 0001), so each of these two PRs lands as one clean commit on its delivery branch. Upstream syncs, by contrast, are history-preserving merges (never squashed/rebased — keep upstream's story). A landed commit is never rewritten.

**Pushing.** **Never** push `main`, `six-pack`, or `upstream` directly without explicit request — push only the two feature branches. `gh` defaults to the `unclebob` upstream remote — always pass `--repo gabadi/swarm-forge`.

**Minimize-diff rule (overriding constraint).** Translate each divergence to its smallest additive form vs current upstream. Do **not** lift whole files from the backup branches for existing files — re-merge the delta onto the *current* upstream file. Net-new files (new roles, templates, skills) may be recovered whole, but you MUST apply the STRIP/FIX edits called out per commit (the backup artifacts predate upstream and carry behavior the ADRs reversed).

**Recovery sources.** Recover exact prior content with `git show <branch>:<path>`. Key sources: `backup/main-pre-reset` (main script layer), `backup/six-pre-reset` (six-pack prompts/templates), `feat/issue-20-a-retro-skill-upgrade` (agent-retro + retro-triage skills), `feat/issue-20-b-bundle-knowledge-injection` (knowledge-promotion spec / curator), `feat/baseline-scenarios-six` (dependency-manifest, cleaner boundary scan), `feat/six-pack-pipeline-order-and-scaffold` (specifier worktree reset), `feat/issue-20-c-curator-six-pack` (8-window conf, integrator). Line numbers are approximate (`~L###`) — they drift; locate by function/section name, not by line.

**Verification approach.** There is no bash unit-test harness in this repo. "Tests" are: (a) `shellcheck` on changed shell files where available, (b) `zsh -n <file>` syntax check, (c) a scratch-project smoke run of the generated artifact (e.g. inspect the bundle `write_agent_instruction_file` produces), and (d) `grep` assertions on prompt/skill text. Each commit states the concrete verification command and expected result. Verify after each commit; a whole-track verification runs before each PR is opened.

**Commit message footer.** End every commit body with:
```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## Commit order (within each branch)

**MAIN branch** (`feat/fork-divergences-main`) — commit in this order; the only hard dependency is C3→C2 (knowledge injection extends the bundle envelope). C1–C6, C8, C11 all edit `swarmforge.sh`, so a linear commit order avoids any in-file conflict:

| # | ADR | What | `swarmforge.sh` region / new file |
|---|-----|------|-----------------------------------|
| C1 | 0019 | auto-permission | `launch_role` |
| C2 | 0017 | bundle inlining | `write_agent_instruction_file` + new `resolve_prompt_bundle` |
| C3 | 0014 | knowledge injection (**after C2**) | `write_agent_instruction_file` |
| C4 | 0012 | per-role model/effort/advisor | `parse_config`, `launch_role` + new `write_worktree_advisor` |
| C5 | 0020 | auto-compaction | `prepare_worktrees` + new `write_worktree_permissions` |
| C6 | 0006 | QA holdout sparse-checkout | `prepare_worktrees` |
| C7 | 0002-ext | executing-entry fields | handoff scripts (`swarmforge/scripts/*.sh`) |
| C8 | 0018 | pinned skill install | new `install_skills`/`ensure_skills_installed` + new `install-pins.conf` |
| C9 | 0013/J | agent-retro skill | new `swarmforge/skills/agent-retro/` |
| C10 | 0021 | retro-triage skill | new `.claude/skills/retro-triage/` |
| C11 | 0003 + O | setup-swarm skill + marker guard + scaffold | new `swarmforge/skills/setup-swarm/` + `swarmforge.sh` guard/gitignore |

**SIX-PACK branch** (`feat/fork-divergences-six-pack`) — commit in this order; the order resolves the shared-file sequencing (`specifier.prompt`: D1,D3,D4,D5,D8,D9,D10 · `QA.prompt`: D1,D2,D3,D6,D7,D9 · `swarmforge.conf`: D8,D9,D10 · `workflow.prompt`: D10,D11):

| # | ADR | What | Touches |
|---|-----|------|---------|
| D1 | 0002 | idle-gate + agent-retro line | all 6 role prompts |
| D2 | 0003 | strip startup-install directives | coder, QA, cleaner, hardener |
| D3 | 0004 | back-routing rule | role prompts |
| D4 | 0009 | spec-header template + specifier | new `templates/feature.feature`, specifier |
| D5 | 0011 | fidelity manifest + specifier | new `dependency-manifest.prompt`, specifier |
| D6 | 0010 | surface harness | `engineering.prompt`, QA |
| D7 | 0005 | refute QA | QA |
| D8 | 0007 | UX engineer | new `ux-engineer.prompt`, coder, specifier, `swarmforge.conf` |
| D9 | 0008 | integrator + specifier stops merging | new `integrator.prompt`, specifier, QA, `swarmforge.conf` |
| D10 | 0013 | curator + chain rewiring | new `curator.prompt`, integrator, specifier, `workflow.prompt`, `swarmforge.conf` |
| D11 | 0015 | platform-feasibility stop rule | `workflow.prompt` |
| D12 | 0016 | cleaner boundary scan | cleaner |
| D13 | — | hardener rendering invariants | hardener |
| D14 | 0018 | root swarm upgrade + self-url | root `swarm` |

---

# MAIN TRACK → PR 1

## Setup: create the main branch

- [ ] **Create the single branch for all MAIN commits**

```bash
git fetch origin && git switch -c feat/fork-divergences-main origin/main
# If origin/main has advanced past the recorded baseline, branch off the tag instead:
#   git switch -c feat/fork-divergences-main fork-base/2026-06-14-main
```
All C1–C11 commits land on this one branch. Do not create per-commit branches. This PR is squash-merged (fork-divergence policy, ADR 0001).

---

## C1: ADR 0019 — autonomous permission mode

**Files:** Modify `swarmforge/scripts/swarmforge.sh` (`launch_role`, the `claude)` and `grok)` arms, ~L433 / ~L442)

- [ ] **Step 1: Locate the two launch arms**

Run: `grep -n "permission-mode acceptEdits" swarmforge/scripts/swarmforge.sh`
Expected: two hits inside `launch_role` — the `claude)` arm and the `grok)` arm.

- [ ] **Step 2: Apply the edit**

Replace `--permission-mode acceptEdits` with `--permission-mode auto` in both arms. (`auto` is a real Claude Code flag value — verified, unlike the phantom `--advisor`. Roles run unattended, so `acceptEdits` bash/tool prompts hang silently; `auto` ships rails — blocks force-push-to-main and mass-delete.)

```bash
sed -i '' 's/--permission-mode acceptEdits/--permission-mode auto/g' swarmforge/scripts/swarmforge.sh
```

- [ ] **Step 3: Verify**

Run: `grep -c "permission-mode auto" swarmforge/scripts/swarmforge.sh; grep -c "acceptEdits" swarmforge/scripts/swarmforge.sh; zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
Expected: `2`, `0`, `SYNTAX_OK`.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/scripts/swarmforge.sh
git commit -m "feat(swarmforge): autonomous permission mode for unattended roles (ADR 0019)"
```

---

## C2: ADR 0017 — prompt-bundle inlining

**Files:** Modify `swarmforge/scripts/swarmforge.sh` (replace `write_agent_instruction_file` ~L389–413; add `resolve_prompt_bundle`)

Upstream emits two naive "read recursively" lines. The fork pre-resolves the constitution + role prompt into one deduplicated XML envelope. **Disentangle from cmux:** port ONLY `resolve_prompt_bundle` + the envelope `write_agent_instruction_file`. Do NOT port `write_deliver_script`/`write_notify_script`/`write_stop_hook`/`MUX_TARGETS`.

- [ ] **Step 1: Read the current naive function**

Run: `grep -n "write_agent_instruction_file" swarmforge/scripts/swarmforge.sh`
Confirm it emits `Read swarmforge/constitution.prompt, then read every file it refers to recursively...` and uses globals `$CONSTITUTION_FILE`, `$ROLES_DIR`, `$WORKING_DIR` (all set upstream).

- [ ] **Step 2: Add `resolve_prompt_bundle` above `write_agent_instruction_file`**

```zsh
resolve_prompt_bundle() {
  local role="$1"
  typeset -a bundle=()
  typeset -A seen=()
  typeset -a queue=("$CONSTITUTION_FILE" "$ROLES_DIR/${role}.prompt")
  local file rel_path ref ref_abs

  while (( ${#queue[@]} > 0 )); do
    file="${queue[1]}"
    shift queue

    rel_path="${file#${WORKING_DIR}/}"
    [[ ${+seen[$rel_path]} -eq 1 ]] && continue
    [[ ! -f "$file" ]] && continue

    seen[$rel_path]=1
    bundle+=("$rel_path")

    while IFS= read -r ref; do
      [[ -z "$ref" ]] && continue
      ref_abs="$WORKING_DIR/$ref"
      [[ ${+seen[$ref]} -eq 0 ]] && queue+=("$ref_abs")
    done < <(grep -oE 'swarmforge/[A-Za-z0-9_./-]+\.prompt' "$file" 2>/dev/null || true)
  done

  printf '%s\n' "${bundle[@]}"
}
```

- [ ] **Step 3: Replace `write_agent_instruction_file` with the envelope form**

```zsh
write_agent_instruction_file() {
  local role="$1"
  local prompt_file="$2"
  typeset -a bundle_files=()
  local rel abs_path

  while IFS= read -r rel; do
    [[ -n "$rel" ]] && bundle_files+=("$rel")
  done < <(resolve_prompt_bundle "$role")

  {
    printf '<swarmforge_agent_context role="%s">\n' "$role"
    printf '<instructions>\n'
    printf 'This prompt bundle is pre-resolved. Do not open or re-read any swarmforge/*.prompt files — all relevant instructions are already included below.\n'
    printf '</instructions>\n'
    for rel in "${bundle_files[@]}"; do
      abs_path="$WORKING_DIR/$rel"
      [[ -f "$abs_path" ]] || continue
      printf '<file path="%s">\n' "$rel"
      cat "$abs_path"
      printf '\n</file>\n'
    done
    printf '</swarmforge_agent_context>\n'
  } > "$prompt_file"
}
```

- [ ] **Step 4: Verify**

Run: `zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
Then confirm the function references only `$CONSTITUTION_FILE`, `$ROLES_DIR`, `$WORKING_DIR` (set in upstream's init/`parse_config`). For a live check, run the swarm in a scratch dir and inspect a generated `$PROMPTS_DIR/<role>.md` — it should be a single `<swarmforge_agent_context>` envelope with deduped `<file>` blocks, no "read recursively" lines.
Expected: `SYNTAX_OK` + a well-formed envelope.

- [ ] **Step 5: Commit**

```bash
git add swarmforge/scripts/swarmforge.sh
git commit -m "feat(swarmforge): pre-resolve role prompt bundle into XML envelope (ADR 0017)"
```

---

## C3: ADR 0014 — `.agents/` knowledge injection (after C2)

**Files:** Modify `swarmforge/scripts/swarmforge.sh` (`write_agent_instruction_file`, as written by C2)

- [ ] **Step 1: Update the preamble line**

In `write_agent_instruction_file`, change the `<instructions>` printf to:

```zsh
    printf 'This prompt bundle is pre-resolved. Do not open or re-read any swarmforge/*.prompt files — all relevant instructions are already included below. Project knowledge files (AGENTS.md and your role file under .agents/roles/) are included below when present.\n'
```

- [ ] **Step 2: Add the knowledge loop**

Add `knowledge` to the locals (`local rel abs_path knowledge`). Insert **after** the bundle-files `for` loop and **before** `printf '</swarmforge_agent_context>\n'`:

```zsh
    for knowledge in "AGENTS.md" ".agents/roles/${role}.md"; do
      abs_path="$WORKING_DIR/$knowledge"
      [[ -f "$abs_path" ]] || continue
      printf '<file path="%s">\n' "$knowledge"
      cat "$abs_path"
      printf '\n</file>\n'
    done
```

- [ ] **Step 3: Acceptance**

Run: `zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
In a scratch project with `AGENTS.md` and `.agents/roles/coder.md`: every role's generated bundle carries `AGENTS.md`; only the coder's carries `.agents/roles/coder.md`; removing both produces bundles with no knowledge blocks and no errors.
Expected: `SYNTAX_OK` + the per-role assertions hold.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/scripts/swarmforge.sh
git commit -m "feat(swarmforge): inject AGENTS.md + .agents/roles into role bundle (ADR 0014)"
```

---

## C4: ADR 0012 — per-role model / effort / advisor

**Files:** Modify `swarmforge/scripts/swarmforge.sh` (`parse_config`, `launch_role`; add arrays + `write_worktree_advisor`)

- [ ] **Step 1: Declare the three arrays**

Next to the existing `ROLES`/`AGENTS`/`SESSIONS` declarations, add:

```zsh
typeset -a ROLE_MODELS=()
typeset -a ROLE_EFFORTS=()
typeset -a ROLE_ADVISORS=()
```

- [ ] **Step 2: Relax field count + parse the kv tail in `parse_config`**

Change `if (( ${#fields[@]} != 4 )); then` → `if (( ${#fields[@]} < 4 )); then`. After the `keyword/role/agent/worktree` assignments, add:

```zsh
    local role_model="" role_effort="" role_advisor="" kv key val kv_i
    for (( kv_i = 5; kv_i <= ${#fields[@]}; kv_i++ )); do
      kv="${fields[$kv_i]}"
      key="${kv%%=*}"
      val="${kv#*=}"
      case "$key" in
        model)   role_model="$val" ;;
        effort)  role_effort="$val" ;;
        advisor) role_advisor="$val" ;;
      esac
    done
```

Where the existing arrays are appended, add the parallel appends:

```zsh
    ROLE_MODELS+=("$role_model")
    ROLE_EFFORTS+=("$role_effort")
    ROLE_ADVISORS+=("$role_advisor")
```

- [ ] **Step 3: Add `write_worktree_advisor`**

```zsh
write_worktree_advisor() {
  local worktree_path="$1"
  local advisor_model="$2"
  local settings_dir="$worktree_path/.claude"
  local settings_file="$settings_dir/settings.local.json"

  mkdir -p "$settings_dir"
  SETTINGS_FILE="$settings_file" ADVISOR_MODEL="$advisor_model" python3 -c '
import json, os
p = os.environ["SETTINGS_FILE"]
cfg = {}
try:
  with open(p) as f: cfg = json.load(f)
except: pass
cfg["advisorModel"] = os.environ["ADVISOR_MODEL"]
with open(p, "w") as f: json.dump(cfg, f, indent=2)
  '
}
```

- [ ] **Step 4: Wire flags into `launch_role`**

After the existing locals, add:

```zsh
  local role_model="${ROLE_MODELS[$index]}"
  local role_effort="${ROLE_EFFORTS[$index]}"
  local role_advisor="${ROLE_ADVISORS[$index]}"
```

After `write_agent_instruction_file "$role" "$prompt_file"`, add:

```zsh
  [[ -n "$role_advisor" ]] && write_worktree_advisor "$role_worktree" "$role_advisor"
```

In the `claude)` arm:

```zsh
      local claude_flags=""
      [[ -n "$role_model" ]]  && claude_flags+=" --model '$role_model'"
      [[ -n "$role_effort" ]] && claude_flags+=" --effort '$role_effort'"
```
then insert `${claude_flags}` immediately after `claude` in `launch_cmd`. Apply the analogue for `copilot)` (`--model`/`--effort`) and `grok)` (`--model`/`--effort`); for `codex)` use `-c model="$role_model"` only when set.

- [ ] **Step 5: Verify**

Run: `zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
Add a temporary conf line `window coder claude coder model=opus effort=high advisor=sonnet` and confirm `parse_config` accepts it; the existing 4-field lines still parse; `advisorModel` lands in the role worktree's `settings.local.json`.
Expected: `SYNTAX_OK` + both 4-field and 7-field lines parse.

- [ ] **Step 6: Commit**

```bash
git add swarmforge/scripts/swarmforge.sh
git commit -m "feat(swarmforge): per-role model/effort/advisor in swarmforge.conf (ADR 0012)"
```

---

## C5: ADR 0020 — auto-compaction on role worktrees

**Files:** Modify `swarmforge/scripts/swarmforge.sh` (add `write_worktree_permissions`; call in `prepare_worktrees`)

- [ ] **Step 1: Add `write_worktree_permissions`**

```zsh
write_worktree_permissions() {
  local worktree_path="$1"
  local settings_dir="$worktree_path/.claude"
  local settings_file="$settings_dir/settings.local.json"

  mkdir -p "$settings_dir"
  SETTINGS_FILE="$settings_file" python3 -c '
import json, os
p = os.environ["SETTINGS_FILE"]
cfg = {}
try:
  with open(p) as f: cfg = json.load(f)
except: pass
cfg["autoCompactEnabled"] = True
cfg.setdefault("env", {})
cfg["env"]["CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"] = "88"
cfg["env"]["CLAUDE_CODE_AUTO_COMPACT_WINDOW"] = "200000"
with open(p, "w") as f: json.dump(cfg, f, indent=2)
  '
}
```

- [ ] **Step 2: Call it from `prepare_worktrees`**

Inside the per-role loop, after the `git worktree add` block (and after C4's advisor call site), add:

```zsh
    write_worktree_permissions "$worktree_path"
```
Both writers JSON-merge `settings.local.json`, so calling both is safe and order-independent.

- [ ] **Step 3: Verify**

Run: `zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
After a scratch run, a role worktree's `.claude/settings.local.json` contains `"autoCompactEnabled": true` and the two `env` overrides (alongside any `advisorModel`).
Expected: `SYNTAX_OK` + merged JSON.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/scripts/swarmforge.sh
git commit -m "feat(swarmforge): enable auto-compaction on role worktrees (ADR 0020)"
```

---

## C6: ADR 0006 — harness-enforced QA holdout (sparse-checkout)

**NET-NEW — no source artifact.** Write fresh. **Files:** Modify `swarmforge/scripts/swarmforge.sh` (`prepare_worktrees`)

- [ ] **Step 1: Identify the loop variables**

Run: `grep -n "worktree add\|WORKTREE_NAMES\|ROLES\[" swarmforge/scripts/swarmforge.sh`
Confirm the role variable in `prepare_worktrees`, the specifier worktree (`specifier`, not `master` — ADR 0008), and the QA role name.

- [ ] **Step 2: Add a pinned QA-path constant**

Near the top config constants (single source of truth, matches the specifier-authored path):

```zsh
QA_HOLDOUT_PATH="${SWARMFORGE_QA_HOLDOUT_PATH:-qa-e2e}"
```

- [ ] **Step 3: Add conditional sparse-checkout after `git worktree add`**

Key on the **role** (not worktree name); exclude the holdout from every worktree except specifier's and QA's:

```zsh
    if [[ "$role" != "specifier" && "$role" != "QA" ]]; then
      git -C "$worktree_path" sparse-checkout init --no-cone >/dev/null 2>&1
      {
        printf '/*\n'
        printf '!/%s/\n' "$QA_HOLDOUT_PATH"
      } > "$worktree_path/.git/info/sparse-checkout" 2>/dev/null \
        || git -C "$worktree_path" sparse-checkout set --no-cone '/*' "!/${QA_HOLDOUT_PATH}/" >/dev/null 2>&1
      git -C "$worktree_path" read-tree -mu HEAD >/dev/null 2>&1 || true
    fi
```
(Substitute the real role-variable name from Step 1 for `$role`. The holdout stays in the commit/tree — only absent from disk — so it survives each role's handoff commit.)

- [ ] **Step 4: Verify holdout invisibility + commit survival**

In a scratch run with a committed `qa-e2e/`: coder/cleaner/architect/hardener worktrees have **no** `qa-e2e/` on disk; specifier + QA **do**; after a role's handoff commit, `git show HEAD:qa-e2e/` still resolves.
Run: `zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
Expected: `SYNTAX_OK` + invisibility/survival hold.

- [ ] **Step 5: Commit**

```bash
git add swarmforge/scripts/swarmforge.sh
git commit -m "feat(swarmforge): sparse-checkout the QA holdout from shaping roles (ADR 0006)"
```

---

## C7: ADR 0002 (extend) — executing-entry context fields

**Files:** Modify upstream's handoff scripts under `swarmforge/scripts/` (the script that writes the `executing` logbook entry + the notify + stop-hook paths)

> ⚠ Reference commit `a133c71` is on the **cmux lineage** (its diff is inside `swarmforge.sh` heredocs that don't exist on pristine upstream). Do **not** cherry-pick — re-author the same field semantics onto upstream's separate handoff scripts.

- [ ] **Step 1: Find the `executing` entry write site**

Run: `grep -rn '"executing"\|status.*executing\|executing' swarmforge/scripts/`
The write site is one of `receive-handoff.sh` / `complete-handoff.sh` / `handoff-lib.sh` / the deliver step. Read the intended semantics:
Run: `git show a133c71`
Expected: the entry must carry `{status, timestamp, message, hash, sender}` instead of `{status, timestamp}`.

- [ ] **Step 2: Add the three fields**

At the write site, extend the JSON object with: `message` (the task message text the delivery already passes), `hash` (the handoff commit hash in scope), `sender` (the sender role resolved from `sessions.tsv` by matching the sender worktree — mirror `notify-agent.sh`'s existing role resolution). Thread `sender` from `notify-agent.sh` → deliver step → stop-hook re-queue path, following upstream's existing argument-passing convention.

- [ ] **Step 3: Verify**

Run: `for f in swarmforge/scripts/*.sh; do zsh -n "$f" || echo "BAD: $f"; done; echo CHECKED`
In a scratch run, trigger a delivery and inspect the `executing` line in `logbook.jsonl`.
Expected: `CHECKED` + the entry carries non-empty `message`, `hash`, `sender`.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/scripts
git commit -m "feat(swarmforge): carry {message,hash,sender} in executing logbook entry (ADR 0002)"
```

---

## C8: ADR 0018 — pinned skill install (main half)

The `upgrade` subcommand + `source-branch` + self-url live in the root `swarm` (six-pack, D14). This is the `main` script half: pin-aware, idempotent skill install at launch (launcher infra-bootstrap — allowed; does not violate idle-gate/setup-first).

**Files:** Create `swarmforge/scripts/install-pins.conf`; modify `swarmforge/scripts/swarmforge.sh`

- [ ] **Step 1: Create `install-pins.conf`**

```bash
cat > swarmforge/scripts/install-pins.conf <<'EOF'
# Pinned external dependency versions for swarm install/upgrade.
# Bump a SHA here and commit on main to pull in a newer version.

# entireio/skills — installed to .claude/skills/ in the target project
ENTIRE_SKILLS_SHA=4c9a02513c3ec6ebabd9a9dc6bd8240854a218ac
EOF
```
Confirm the SHA against `backup/main-pre-reset:swarmforge/scripts/install-pins.conf` and bump if it has moved.

- [ ] **Step 2: Add `install_skills` + `ensure_skills_installed`**

Run: `git show backup/main-pre-reset:swarmforge/scripts/swarmforge.sh | grep -n "install_skills\|ensure_skills_installed"`
Add `install_skills()` (sources `install-pins.conf`; copies the in-repo `agent-retro` skill into `.claude/skills/`; fetches entire's skills tarball at `$ENTIRE_SKILLS_SHA` into `.claude/skills/`; writes the SHA to `$STATE_DIR/skills-installed`; warns and continues if offline) and `ensure_skills_installed()` (returns early if the sentinel matches the pinned SHA, else calls `install_skills`). Use the canonical bodies from `backup/main-pre-reset` (`~L946`), kept additive.

- [ ] **Step 3: Call it in the launch flow**

After config is parsed and `$STATE_DIR` is known, add:

```zsh
ensure_skills_installed
```

- [ ] **Step 4: Verify**

Run: `zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
A second launch is a no-op (sentinel matches); an offline launch warns rather than failing.
Expected: `SYNTAX_OK` + idempotent re-run.

- [ ] **Step 5: Commit**

```bash
git add swarmforge/scripts/swarmforge.sh swarmforge/scripts/install-pins.conf
git commit -m "feat(swarmforge): pin-aware idempotent skill install at launch (ADR 0018)"
```

---

## C9: ADR 0013 / Idea J — agent-retro skill (net-new)

upstream/main has no `skills/` dir — this is a net-new add. Source = `feat/issue-20-a-retro-skill-upgrade:swarmforge/skills/agent-retro/`.

**Files:** Create `swarmforge/skills/agent-retro/`

- [ ] **Step 1: Recover the skill files**

```bash
for f in $(git ls-tree -r --name-only feat/issue-20-a-retro-skill-upgrade -- swarmforge/skills/agent-retro); do
  mkdir -p "$(dirname "$f")"
  git show "feat/issue-20-a-retro-skill-upgrade:$f" > "$f"
done
```

- [ ] **Step 2: Verify the four locked behaviors**

```bash
grep -c "pending-curation" swarmforge/skills/agent-retro/SKILL.md   # >= 1
grep -ci "scope" swarmforge/skills/agent-retro/SKILL.md             # >= 2 (tag + table column)
grep -ci "capture" swarmforge/skills/agent-retro/SKILL.md           # >= 1
grep -c "session info --transcript\|.claude/projects" swarmforge/skills/agent-retro/SKILL.md  # >= 1
```
Expected: all thresholds met. If any is 0, re-check the source branch.

- [ ] **Step 3: Commit**

```bash
git add swarmforge/skills/agent-retro
git commit -m "feat(swarmforge): add agent-retro skill — scoped, capture-first, autonomous (ADR 0013)"
```

---

## C10: ADR 0021 — retro-triage skill (net-new, byte-identical)

Lives under `.claude/skills/` (operator-invoked), distinct from `swarmforge/skills/`. **Files:** Create `.claude/skills/retro-triage/SKILL.md`

- [ ] **Step 1: Recover byte-identical**

```bash
mkdir -p .claude/skills/retro-triage
git show feat/issue-20-a-retro-skill-upgrade:.claude/skills/retro-triage/SKILL.md > .claude/skills/retro-triage/SKILL.md
```

- [ ] **Step 2: Verify**

```bash
git diff --no-index <(git show feat/issue-20-a-retro-skill-upgrade:.claude/skills/retro-triage/SKILL.md) .claude/skills/retro-triage/SKILL.md && echo IDENTICAL
wc -l .claude/skills/retro-triage/SKILL.md
```
Expected: `IDENTICAL`, ~219 lines.

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/retro-triage
git commit -m "feat: restore retro-triage skill (ADR 0021)"
```

---

## C11: ADR 0003 + Idea O — setup-swarm skill, marker guard, scaffold

NET-NEW skill design (no backup artifact). **Files:** Create `swarmforge/skills/setup-swarm/SKILL.md`; modify `swarmforge/scripts/swarmforge.sh`

- [ ] **Step 1: Read the design recovery doc**

Run: `cat docs/migrations/0003-setup-skill-sources.md`
Confirm: setup is **setup-first** (operator runs `/setup-swarm` first); `./swarm` only **guards** on `.swarmforge/setup-complete` and refuses if absent (never auto-runs setup); skill named `setup-swarm`; Idea O folds in; the `entire` skill pins are NOT here (that is C8).

- [ ] **Step 2: Author `setup-swarm/SKILL.md`**

Mirror `agent-retro`'s SKILL.md shape; cover, per the design doc:
- **Stack detection** (reason about the language → which quality tools/gates to install — *why* setup is a skill, not a script; don't over-prescribe the mechanism).
- Install the project's mutation/CRAP/DRY tools (those stripped from cleaner/hardener/QA) and APS `gherkin-parser`/`gherkin-mutator` (stripped from coder/hardener).
- Session tracking: `entire enable --no-github --telemetry=false`, then `entire agent add <backend>` per unique backend in `swarmforge.conf` column 3; warn-and-continue if `entire` absent.
- Permission allow-rules to `.claude/settings.json` (`Bash(gh pr merge*)` for integrator, `Bash(git reset --hard origin/*)` for specifier) — a small, advisory set, not a load-bearing whitelist (ADR 0019 `auto` already ships rails).
- Scaffold: ensure `.gitignore` covers `logbook.jsonl`, `tmp/`, `.swarmforge/`; probe the default branch (`git symbolic-ref refs/remotes/origin/HEAD`) and record it for the specifier's per-feature reset.
- Emit the swarm-ready marker `.swarmforge/setup-complete` (content: timestamp + swarmforge SHA — impl detail).

- [ ] **Step 3: Add the marker guard to `swarmforge.sh`**

Early in the launch flow (before any role launch; distinct from the `ensure_skills_installed` launcher bootstrap), add:

```zsh
if [[ ! -f "$STATE_DIR/setup-complete" ]]; then
  echo -e "${RED}Error:${RESET} project is not swarm-ready. Run /setup-swarm first." >&2
  exit 1
fi
```
The guard never runs setup; it only refuses.

- [ ] **Step 4: Expand the gitignore/excludes scaffold (Idea O)**

In `ensure_initial_gitignore`, add `logbook.jsonl`, `tmp/` (plus backup's `swarmtools/`/`logs/`/`agent_context/` if still relevant) — each as an idempotent `grep -qx || append` block and in the initial-creation heredoc. In `ensure_runtime_git_excludes`, expand the `for pattern in ...` loop to the same set. Add `remove_nonessential_clone_files` (recover from `backup/main-pre-reset`) and call it once in the init flow.

- [ ] **Step 5: Verify**

Run: `zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK`
Launching without the marker exits with "Run /setup-swarm first"; creating `.swarmforge/setup-complete` lets launch proceed; running twice doesn't duplicate `.gitignore` lines.
Expected: `SYNTAX_OK` + guard + idempotent gitignore.

- [ ] **Step 6: Commit**

```bash
git add swarmforge/skills/setup-swarm swarmforge/scripts/swarmforge.sh
git commit -m "feat(swarmforge): setup-swarm skill + swarm-ready marker guard + scaffold (ADR 0003, Idea O)"
```

---

## Finalize PR 1 (MAIN)

- [ ] **Step 1: Whole-track verification**

```bash
zsh -n swarmforge/scripts/swarmforge.sh && echo SYNTAX_OK
git diff --stat origin/main   # review: only intended files changed, all additive
```
Expected: `SYNTAX_OK`; the diff touches only `swarmforge/scripts/*`, `swarmforge/skills/*`, `.claude/skills/retro-triage/*` — no role prompts, no conf (those are PR 2).

- [ ] **Step 2: Push the branch**

```bash
git push -u origin feat/fork-divergences-main
```

- [ ] **Step 3: Open the single PR**

```bash
gh pr create --base main --repo gabadi/swarm-forge \
  --title "feat: fork divergences — main script + skill layer" \
  --body "Re-applies the main-side fork divergences on pristine upstream, one commit per ADR: 0019 auto-permission, 0017 bundle inlining, 0014 knowledge injection, 0012 per-role config, 0020 auto-compaction, 0006 QA holdout, 0002 executing-fields, 0018 skill install, 0013 agent-retro, 0021 retro-triage, 0003 setup-swarm + Idea O. cmux dropped; four-pack frozen. See docs/superpowers/plans/2026-06-14-fork-divergence-implementation.md and docs/fork-change-manifest.md (Sections A + C)."
```

---

# SIX-PACK TRACK → PR 2

## Setup: create the six-pack branch

- [ ] **Create the single branch for all SIX-PACK commits**

```bash
git fetch origin && git switch -c feat/fork-divergences-six-pack origin/six-pack
# If origin/six-pack has advanced past the recorded baseline, branch off the tag instead:
#   git switch -c feat/fork-divergences-six-pack fork-base/2026-06-14-six-pack
```
All D1–D14 commits land on this one branch. This PR is squash-merged (fork-divergence policy, ADR 0001).

---

## D1: ADR 0002 — idle-gate + agent-retro line (all roles)

**Files:** Modify `swarmforge/roles/{specifier,coder,cleaner,architect,hardender,QA}.prompt`

- [ ] **Step 1: Add the idle-gate line**

After the `You are the <role>.` opening of each of the six prompts, insert a blank line then:

```
Wait for a handoff. Do not act without one.
```

- [ ] **Step 2: Add the agent-retro line**

As the last bullet of each role's Handoff section:

```
- Run `agent-retro` before going idle.
```

- [ ] **Step 3: Verify**

```bash
for r in specifier coder cleaner architect hardender QA; do
  grep -q "Wait for a handoff. Do not act without one." "swarmforge/roles/$r.prompt" || echo "MISSING idle-gate: $r"
  grep -q "agent-retro\` before going idle" "swarmforge/roles/$r.prompt" || echo "MISSING retro: $r"
done; echo CHECKED
```
Expected: only `CHECKED`.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/roles
git commit -m "feat(roles): idle-gate + agent-retro-before-idle on every role (ADR 0002)"
```

---

## D2: ADR 0003 — strip startup-install directives

Install work moves to the setup-swarm skill (C11). **Files:** Modify `swarmforge/roles/{coder,QA,cleaner,hardender}.prompt`

- [ ] **Step 1: Strip the directives**

- `coder.prompt`: remove the entire `## Acceptance Pipeline` block (the "At startup, make sure the normal acceptance pipeline …" bullets, ~L8–14).
- `QA.prompt`: remove the `## Startup Tools` section (~L6–7).
- `cleaner.prompt`: remove the "At startup, install the language mutation, CRAP, and DRY tools …" line (~L19).
- `hardender.prompt`: remove the `## Startup Tools` section + APS build line (~L7–10).

- [ ] **Step 2: Verify**

```bash
grep -rn "At startup" swarmforge/roles/ ; echo "--- (expect no startup-install directives remain)"
```
Expected: no remaining "At startup, install/make-ready" directives.

- [ ] **Step 3: Commit**

```bash
git add swarmforge/roles
git commit -m "refactor(roles): remove startup install directives — moved to setup-swarm (ADR 0003)"
```

---

## D3: ADR 0004 — back-routing rule

No backup source — author fresh from ADR 0004. **Files:** Modify the rework-owning role prompts (coder, cleaner, architect, hardender, QA)

- [ ] **Step 1: Read the ADR for the exact mechanic**

Run: `cat docs/adr/0004-rework-routes-back.md`
Confirm: structural finding (re-opens an earlier stage's job) → routes to that origin stage, carried in the handoff; local work stays with the finder; a single finding bounces back at most once; a feature tolerates N=3 cycles total (routing count in the handoff trail); on exceeding, stop and ask the user.

- [ ] **Step 2: Insert a `## Rework Routing` section before each role's Handoff**

```
## Rework Routing
- A structural finding — one that re-opens an earlier stage's decision (an ambiguous or missing spec, a weak or missing test, a design that cannot hold the required behavior) — routes back to the stage that owns that decision, carried in the handoff.
- Local work you can resolve without re-opening an earlier decision stays with you; fix it in place.
- A single finding bounces back at most once. A feature tolerates at most three back-route cycles total (N=3), tracked by the routing count in the handoff trail. On the fourth, stop and ask the user.
```

- [ ] **Step 3: Verify**

```bash
for r in coder cleaner architect hardender QA; do grep -q "## Rework Routing" "swarmforge/roles/$r.prompt" || echo "MISSING: $r"; done; echo CHECKED
```
Expected: only `CHECKED`.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/roles
git commit -m "feat(roles): structural-finding back-routing with N=3 cap (ADR 0004)"
```

---

## D4: ADR 0009 — spec-header template + specifier wiring

**Files:** Create `swarmforge/templates/feature.feature`; modify `swarmforge/roles/specifier.prompt`

- [ ] **Step 1: Recover the template**

```bash
mkdir -p swarmforge/templates
git show backup/six-pre-reset:swarmforge/templates/feature.feature > swarmforge/templates/feature.feature
```
Confirm all eight comment sections: `TRACKING`, `CONTRACT`, `CONSTRAINTS`, `SEQUENCING`, `NFR`, `SIDE EFFECTS`, `SCOPE`, `UX INTENT`.

- [ ] **Step 2: Wire the specifier**

In Feature Workflow phase 1: start from the template and address all eight header sections (several may resolve to `none` — a deliberate answer) before scenarios. Change any "seven" header-count wording to **"eight"** / "all".

- [ ] **Step 3: Verify**

```bash
grep -c "^  # \(TRACKING\|CONTRACT\|CONSTRAINTS\|SEQUENCING\|NFR\|SIDE EFFECTS\|SCOPE\|UX INTENT\)" swarmforge/templates/feature.feature  # 8
grep -n "template\|eight" swarmforge/roles/specifier.prompt
grep -c "seven" swarmforge/roles/specifier.prompt  # 0
```
Expected: 8 sections; specifier references the template + "eight"; no "seven".

- [ ] **Step 4: Commit**

```bash
git add swarmforge/templates/feature.feature swarmforge/roles/specifier.prompt
git commit -m "feat(spec): 8-section feature template; specifier starts from it (ADR 0009)"
```

---

## D5: ADR 0011 — fidelity manifest + specifier check

**Files:** Create `swarmforge/dependency-manifest.prompt`; modify `swarmforge/roles/specifier.prompt`

- [ ] **Step 1: Recover the manifest (with its Rules section)**

```bash
git show feat/baseline-scenarios-six:swarmforge/dependency-manifest.prompt > swarmforge/dependency-manifest.prompt
```
⚠ From `feat/baseline-scenarios-six`, NOT `obs-harness-six` (which over-deleted the Rules section). Confirm the 3 tier defs, a `Rules for every declared dependency:` section, and a `## Dependencies` body of `(none)`.

- [ ] **Step 2: Wire the specifier**

Add a `## Dependency Manifest` instruction before Feature Workflow: read the manifest before scenarios; on a scenario touching an undeclared external system → stop, propose name/tier/implementation/gaps, wait for approval before adding the entry; never write scenarios resting on an undeclared dependency or a declared gap. Recover exact wording from `backup/six-pre-reset:.../specifier.prompt` or `feat/issue-20-c:.../specifier.prompt` (NOT pipeline-order, which dropped it).

- [ ] **Step 3: Verify**

```bash
grep -ci "tier" swarmforge/dependency-manifest.prompt          # >= 3
grep -q "Rules for every declared dependency" swarmforge/dependency-manifest.prompt && echo RULES_OK
grep -q "dependency-manifest" swarmforge/roles/specifier.prompt && echo SPECIFIER_WIRED
```
Expected: tiers present, `RULES_OK`, `SPECIFIER_WIRED`.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/dependency-manifest.prompt swarmforge/roles/specifier.prompt
git commit -m "feat(spec): dependency fidelity manifest + specifier propose-on-undeclared (ADR 0011)"
```

---

## D6: ADR 0010 — surface harness (engineering article + QA)

**Files:** Modify `swarmforge/constitution/articles/engineering.prompt`, `swarmforge/roles/QA.prompt`

- [ ] **Step 1: Add the surface-tool table to `engineering.prompt`**

Recover the table + context-driven acquisition rule from `backup/six-pre-reset:swarmforge/constitution/articles/engineering.prompt` and merge onto current upstream (a `## Surface Tools` section: tmux/PTY · Playwright · HTTP client · ingress event-injection; live-verification roles pick the minimal sufficient tool per surface).

- [ ] **Step 2: Edit QA for surface-harness verification**

In `QA.prompt`:
- Replace "through the user interface only" → "through the project surface harness only".
- Add: every Expected bullet maps to a harness assertion, or is `NOT AUTOMATED — <reason>`; asserting constants/config never satisfies a behavioral assertion.
- Add: re-execute the committed `observation-harness/` scenarios before final verification; a user-facing surface with no scenarios routes back (per D3).
- Add the per-surface **baseline scenario** requirement (idle stability / no console errors / no-op event = no state change).

- [ ] **Step 3: Verify**

```bash
grep -qi "surface" swarmforge/constitution/articles/engineering.prompt && echo ENG_OK
grep -q "project surface harness only" swarmforge/roles/QA.prompt && echo QA_SURFACE_OK
grep -q "observation-harness" swarmforge/roles/QA.prompt && echo QA_OBS_OK
grep -c "user interface only" swarmforge/roles/QA.prompt  # 0
```
Expected: `ENG_OK`, `QA_SURFACE_OK`, `QA_OBS_OK`, zero "user interface only".

- [ ] **Step 4: Commit**

```bash
git add swarmforge/constitution/articles/engineering.prompt swarmforge/roles/QA.prompt
git commit -m "feat(qa): declared surface-harness verification + baseline scenarios (ADR 0010)"
```

---

## D7: ADR 0005 — refuting QA posture

No backup source for the refute posture — author fresh; merge with D6's surface wording. **Files:** Modify `swarmforge/roles/QA.prompt`

- [ ] **Step 1: Replace the confirm posture with refute**

Replace the "Fix bugs found by the QA suite or final verification." line and surrounding confirm framing with:

```
- Assume the build does not meet the spec and the acceptance tests are too weak to notice, until proven otherwise. Attack the specified contract — try to make it fail within the spec — rather than run a checklist and confirm.
- Stay bounded by the spec: a gap the spec never settled is not a QA pass/fail; route it back to the specifier (per Rework Routing).
- Enforce conversion fidelity: a QA procedure converted into an executable script must encode the procedure's full intent. A green script that asserts nothing is test theater and is itself a defect.
- A structural finding (weak/missing test, ambiguous spec) routes back; a local defect you can fix without re-opening an earlier stage you fix in place.
```

- [ ] **Step 2: Confirm against the ADR**

Run: `cat docs/adr/0005-qa-refutes-not-confirms.md`
Ensure the text matches the ADR's intent (refute, spec-bounded, conversion fidelity / no test theater).

- [ ] **Step 3: Verify**

```bash
grep -qi "assume the build does not meet the spec" swarmforge/roles/QA.prompt && echo REFUTE_OK
grep -ci "test theater\|asserts nothing" swarmforge/roles/QA.prompt  # >= 1
grep -c "Fix bugs found by the QA suite" swarmforge/roles/QA.prompt  # 0
```
Expected: `REFUTE_OK`, conversion-fidelity line present, old confirm line gone.

- [ ] **Step 4: Commit**

```bash
git add swarmforge/roles/QA.prompt
git commit -m "feat(qa): refute posture — attack the contract, no test theater (ADR 0005)"
```

---

## D8: ADR 0007 — UX Engineer role

**Files:** Create `swarmforge/roles/ux-engineer.prompt`; modify `swarmforge/roles/coder.prompt`, `swarmforge/roles/specifier.prompt`, `swarmforge/swarmforge.conf`

- [ ] **Step 1: Recover the ux-engineer role**

```bash
git show backup/six-pre-reset:swarmforge/roles/ux-engineer.prompt > swarmforge/roles/ux-engineer.prompt
```
⚠ From `backup/six-pre-reset` (≡ `origin/feat/obs-harness-six`), NOT pipeline-order/baseline (they lack the `observation-harness/` commit step). **STRIP** DESIGN.md scaffold-on-absence + walk-up; make DESIGN.md fix-authority conditional on a feature-file reference, not tree discovery. Ensure it carries: the idle-gate line, the N=3 back-route to coder, the `observation-harness/` commit step, golden snapshots + rendering invariants, the `## Visual quality standards` block (WCAG 4.5:1 / 3:1), notify→cleaner.

- [ ] **Step 2: Wire coder + specifier**

- `coder.prompt`: add a "read the feature's `## UX Intent` and implement from it alongside the Gherkin" line; change handoff `notify the cleaner` → `notify the ux-engineer`.
- `specifier.prompt`: add UX INTENT authoring (it authors the feature file's `## UX Intent` section — concrete observable statements across Visual Composition / Information Hierarchy / Interaction Feel / State Transitions). STRIP any DESIGN.md scaffold/walk-up here too (reference-from-feature-file only).

- [ ] **Step 3: Add the conf window after coder**

In `swarmforge.conf`, after the coder line:
```
window ux-engineer codex ux-engineer
```

- [ ] **Step 4: Verify**

```bash
grep -q "Wait for a handoff" swarmforge/roles/ux-engineer.prompt && echo UX_IDLE_OK
grep -q "observation-harness" swarmforge/roles/ux-engineer.prompt && echo UX_OBS_OK
grep -c "scaffold" swarmforge/roles/ux-engineer.prompt  # 0
grep -q "notify the ux-engineer" swarmforge/roles/coder.prompt && echo CODER_OK
grep -q "window ux-engineer" swarmforge/swarmforge.conf && echo CONF_OK
```
Expected: `UX_IDLE_OK`, `UX_OBS_OK`, zero scaffold, `CODER_OK`, `CONF_OK`.

- [ ] **Step 5: Commit**

```bash
git add swarmforge/roles/ux-engineer.prompt swarmforge/roles/coder.prompt swarmforge/roles/specifier.prompt swarmforge/swarmforge.conf
git commit -m "feat(roles): UX Engineer after coder; UX Intent authoring + read (ADR 0007)"
```

---

## D9: ADR 0008 — integrator role + specifier stops merging

**Files:** Create `swarmforge/roles/integrator.prompt`; modify `swarmforge/roles/specifier.prompt`, `swarmforge/roles/QA.prompt`, `swarmforge/swarmforge.conf`

- [ ] **Step 1: Recover the integrator role + apply the FIX**

```bash
git show backup/six-pre-reset:swarmforge/roles/integrator.prompt > swarmforge/roles/integrator.prompt
```
⚠ From `backup/six-pre-reset` (≡ `feat/issue-20-c`), NOT baseline-scenarios-six (still says "notify specifier"). **FIX step 7** to: `Notify the curator that the feature has landed. Include the specifier handoff name and the post-merge master commit hash.` Confirm: one PR/feature, autofix-lint-only, branch → `gh pr create` → watch CI → green `gh pr merge --squash --delete-branch` + post-merge gate, CI-red routing (tests→coder, coverage/CRAP/DRY→cleaner, arch→architect; autofix doesn't count; N=3 then `FAILED: depth cap reached`), idle-gate line, agent-retro line.

- [ ] **Step 2: Specifier stops merging + per-feature reset**

In `specifier.prompt`:
- Drop the merge step (upstream's "merge the changes and ask the user", ~L36); replace the completion line with a placeholder D10 finalizes — for now: "When the work is landed, ask the user for the next feature to add."
- Add the per-feature worktree reset: on receiving a handoff, `git reset --hard "origin/$(git symbolic-ref refs/remotes/origin/HEAD | sed 's|refs/remotes/origin/||')"` in the specifier's own worktree (recover the exact form from `feat/six-pack-pipeline-order-and-scaffold`). STRIP any `git merge --ff-only origin/master` startup line.

- [ ] **Step 3: QA hands off to integrator + conf windows**

- `QA.prompt`: change the final handoff to `notify the integrator` (replacing the broadcast list).
- `swarmforge.conf`: change line 1 `window specifier codex master` → `window specifier codex specifier`; insert after QA: `window integrator codex integrator`.

- [ ] **Step 4: Verify**

```bash
grep -q "Notify the curator" swarmforge/roles/integrator.prompt && echo INT_FIX_OK
grep -q "post-merge master commit hash" swarmforge/roles/integrator.prompt && echo INT_HASH_OK
grep -q "notify the integrator" swarmforge/roles/QA.prompt && echo QA_INT_OK
grep -q "symbolic-ref" swarmforge/roles/specifier.prompt && echo SPEC_RESET_OK
grep -q "window specifier codex specifier" swarmforge/swarmforge.conf && echo CONF_SPEC_OK
grep -q "window integrator" swarmforge/swarmforge.conf && echo CONF_INT_OK
grep -c "codex master" swarmforge/swarmforge.conf  # 0
```
Expected: all six `*_OK`, zero `codex master`.

- [ ] **Step 5: Commit**

```bash
git add swarmforge/roles/integrator.prompt swarmforge/roles/specifier.prompt swarmforge/roles/QA.prompt swarmforge/swarmforge.conf
git commit -m "feat(roles): terminal integrator; specifier stops merging, runs own worktree (ADR 0008)"
```

---

## D10: ADR 0013 — curator role + chain rewiring

Authoritative source = the locked spec's PR-C2 block (budgets **60/40**, NOT the stale 150/300 on artifact branches). **Files:** Create `swarmforge/roles/curator.prompt`; modify `swarmforge/roles/integrator.prompt`, `swarmforge/roles/specifier.prompt`, `swarmforge/constitution/articles/workflow.prompt`, `swarmforge/swarmforge.conf`

- [ ] **Step 1: Extract the curator from the locked spec**

Run: `git show feat/issue-20-b-bundle-knowledge-injection:docs/specs/issue-20-knowledge-promotion-loop.md`
Copy the **PR-C2 verbatim block** into `swarmforge/roles/curator.prompt`. Confirm: idle-gate; writes only `AGENTS.md` + `.agents/`; sources `~/.claude/worklog/retros/*.md`; the routing ladder (enforcement-gate backlog → AGENTS.md ≤60 → role files ≤40 → references → skills-on-2nd → upstream → ledger); ledger line `date | session-id | role | failure-class | verdict | summary`; lifecycle (empty-run pass-through, knowledge branch, self-merging PR with metric line, move retros to `processed/`, notify specifier); 9-check per-item algorithm. **Budgets must read 60 and 40.**

- [ ] **Step 2: Rewire the chain**

- `integrator.prompt`: confirm step 7 notifies the curator (done in D9); fix if drifted.
- `specifier.prompt`: change the wait line to "When the **curator** notifies you that the job is complete, run the per-feature reset, then ask the user for the next feature. The curator's handoff means the knowledge PR for the previous feature has already landed."
- `workflow.prompt`: append: "The landing chain is integrator → curator → specifier. The curator promotes retro knowledge before the specifier is released; an empty curation run notifies the specifier immediately — the pipeline never stalls on the curator."
- `swarmforge.conf`: append last: `window curator codex curator`.

- [ ] **Step 3: Verify**

```bash
grep -q "Wait for a handoff" swarmforge/roles/curator.prompt && echo CUR_IDLE_OK
grep -Eq "60" swarmforge/roles/curator.prompt && grep -Eq "40" swarmforge/roles/curator.prompt && echo BUDGETS_OK
grep -c "150\|300" swarmforge/roles/curator.prompt  # 0
grep -q "When the curator notifies you" swarmforge/roles/specifier.prompt && echo SPEC_CUR_OK
grep -qi "integrator.*curator.*specifier" swarmforge/constitution/articles/workflow.prompt && echo WF_OK
grep -c "^window" swarmforge/swarmforge.conf  # 9
```
Expected: `CUR_IDLE_OK`, `BUDGETS_OK`, zero 150/300, `SPEC_CUR_OK`, `WF_OK`, and **9** windows (specifier, coder, ux-engineer, cleaner, architect, hardender, QA, integrator, curator).

- [ ] **Step 4: Commit**

```bash
git add swarmforge/roles/curator.prompt swarmforge/roles/specifier.prompt swarmforge/constitution/articles/workflow.prompt swarmforge/swarmforge.conf
git commit -m "feat(roles): terminal curator; integrator->curator->specifier chain (ADR 0013)"
```

---

## D11: ADR 0015 — platform-feasibility stop rule

**Files:** Modify `swarmforge/constitution/articles/workflow.prompt`

- [ ] **Step 1: Add the stop rule**

Append to `workflow.prompt`:

```
## Platform Feasibility
- When the spec and the platform conflict — the spec calls for a capability the target platform does not provide — stop and report instead of working around it. A workaround comment ("we can't do X here, so we do Y") is a defect, not a resolution. Wait for a spec revision.
```

- [ ] **Step 2: Verify**

```bash
grep -qi "platform" swarmforge/constitution/articles/workflow.prompt && grep -qi "workaround.*defect" swarmforge/constitution/articles/workflow.prompt && echo OK
```
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add swarmforge/constitution/articles/workflow.prompt
git commit -m "feat(workflow): platform-feasibility stop rule (ADR 0015)"
```

---

## D12: ADR 0016 — cleaner boundary-file scan

**Files:** Modify `swarmforge/roles/cleaner.prompt`

- [ ] **Step 1: Add the boundary-file rule**

Recover the cleanest wording from `feat/baseline-scenarios-six:swarmforge/roles/cleaner.prompt`. After the ">100 mutation sites → split" rule, add:

```
- Also run the mutation scan/count mode on boundary files (the environmentally unsuitable modules excluded from the test tools). If a boundary file exceeds ~15 mutation sites, it holds implementation logic, not adaptation — extract that logic to a testable module before handoff.
- Treat a test that asserts only a stripped or simplified view of output (e.g. ANSI-stripped text when the real output carries escape codes) as not covering the un-stripped behavior. Add coverage for the full output.
```

- [ ] **Step 2: Verify**

```bash
grep -qi "boundary" swarmforge/roles/cleaner.prompt && grep -q "15" swarmforge/roles/cleaner.prompt && echo BOUNDARY_OK
grep -qi "stripped" swarmforge/roles/cleaner.prompt && echo STRIPPED_OK
```
Expected: `BOUNDARY_OK`, `STRIPPED_OK`.

- [ ] **Step 3: Commit**

```bash
git add swarmforge/roles/cleaner.prompt
git commit -m "feat(cleaner): boundary-file mutation scan at ~15 sites; stripped-view anti-pattern (ADR 0016)"
```

---

## D13: hardener rendering-invariant property tests (manifest row, no ADR)

Unmanifested divergence found in audit; consistent with ADR 0007/0010. **Files:** Modify `swarmforge/roles/hardender.prompt`

- [ ] **Step 1: Add the rendering-invariant line**

Recover the exact text from `backup/six-pre-reset:swarmforge/roles/hardender.prompt` (~L18) and merge it in (don't lift the whole file). Rule: for pure rendering functions (state → string, no side effects), add property tests asserting structural invariants — required elements present per state, character set bounded to the declared vocabulary, mutually exclusive states never co-rendered. Confirm D2 already stripped Startup Tools and the unauthorized "merge all queued architect handoffs together" line is absent (keep upstream's sorted-filename batch).

- [ ] **Step 2: Verify**

```bash
grep -qi "rendering" swarmforge/roles/hardender.prompt && grep -qi "property test\|invariant" swarmforge/roles/hardender.prompt && echo OK
grep -c "merge all queued architect handoffs" swarmforge/roles/hardender.prompt  # 0
```
Expected: `OK`, zero unauthorized merge-all line.

- [ ] **Step 3: Commit**

```bash
git add swarmforge/roles/hardender.prompt
git commit -m "feat(hardener): property tests for pure rendering functions (manifest row)"
```

---

## D14: ADR 0018 — root `swarm` upgrade subcommand + self-url

The main script half (skill install) is C8. This is the runnable-branch half. **Files:** Modify the root `swarm` bootstrap (exists on `six-pack`)

- [ ] **Step 1: Inspect current + recover the target deltas**

Run: `git show origin/six-pack:swarm | head -60`
Run: `git show 8994322:swarm 2>/dev/null | head -120` (adds `upgrade`/`write_source_branch`/`download_from_main`) and `git show ded6019:swarm 2>/dev/null | head -40` (self-url).
Merge the minimal deltas onto the current six-pack root `swarm`:
- `SCRIPTS_REPO="${SWARMFORGE_SCRIPTS_REPO:-gabadi/swarm-forge}"` (self-referencing; replaces hardcoded `unclebob/swarm-forge`).
- `download_from_main` (refresh scripts + skills from `main`).
- `write_source_branch` (record the runnable source branch in `.swarmforge/source-branch`).
- The `upgrade` subcommand: refresh scripts(main) + prompts(`source-branch`) + force skill reinstall (clear `.swarmforge/skills-installed`).

- [ ] **Step 2: Verify**

```bash
grep -q "gabadi/swarm-forge" swarm && echo SELF_URL_OK
{ grep -q "upgrade)" swarm || grep -q '"upgrade"' swarm; } && echo UPGRADE_OK
{ zsh -n swarm 2>/dev/null || bash -n swarm; } && echo SYNTAX_OK
```
Expected: `SELF_URL_OK`, `UPGRADE_OK`, `SYNTAX_OK`.

- [ ] **Step 3: Commit**

```bash
git add swarm
git commit -m "feat(swarm): self-url + upgrade subcommand with source-branch tracking (ADR 0018)"
```

---

## Finalize PR 2 (SIX-PACK)

- [ ] **Step 1: Whole-track verification**

```bash
grep -c "^window" swarmforge/swarmforge.conf   # 9, in order
for r in specifier coder ux-engineer cleaner architect hardender QA integrator curator; do
  test -f "swarmforge/roles/$r.prompt" || echo "MISSING role file: $r"
done; echo ROLES_CHECKED
git diff --stat origin/six-pack   # review: only prompts/articles/templates/conf/swarm changed
```
Expected: 9 windows; all 9 role files present; `ROLES_CHECKED`; the diff touches only six-pack-owned files.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin feat/fork-divergences-six-pack
```

- [ ] **Step 3: Open the single PR**

```bash
gh pr create --base six-pack --repo gabadi/swarm-forge \
  --title "feat: fork divergences — six-pack prompts + constitution + conf" \
  --body "Re-applies the six-pack fork divergences on pristine upstream, one commit per ADR: 0002 idle-gate, 0003 startup-strip, 0004 back-routing, 0009 spec header, 0011 fidelity manifest, 0010 surface harness, 0005 refute QA, 0007 UX engineer, 0008 integrator, 0013 curator, 0015 platform-feasibility, 0016 cleaner boundary scan, hardener invariants, 0018 root swarm upgrade. Final pipeline: specifier→coder→ux-engineer→cleaner→architect→hardener→QA→integrator→curator (9 windows). DESIGN.md reference-only; curator budgets 60/40; four-pack frozen. See docs/superpowers/plans/2026-06-14-fork-divergence-implementation.md and docs/fork-change-manifest.md (Section B)."
```

---

## Out of scope (explicitly NOT implemented)

- **four-pack PR** — frozen (manifest 2026-06-14): pure merge-mirror of `upstream/four-pack`. The issue-20 spec's "PR D on four-pack" is **dropped**.
- **cmux multiplexer** (`swarm-mux.sh`, `write_deliver_script`/`write_notify_script`/`write_stop_hook`, `MUX_TARGETS`) — DROPPED; stay on upstream's tmux harness.
- **Ideas G, H, I** — genuinely rejected, no recovery.
- **DESIGN.md scaffolding** — ADR 0007 wins: reference-from-feature-file only; recovered roles STRIP scaffold-on-absence + walk-up.
- **curator budgets 150/300** — superseded by the locked spec's 60/40.

---

## Self-Review

**Spec coverage** (manifest sections A/B/C + cross-cutting):
- Section A (main → PR 1): 0006→C6, 0012→C4, 0014→C3, 0013-skill→C9, 0003→C11 ✓
- Section B (six-pack → PR 2): 0002→D1, 0009→D4, 0011→D5, 0010→D6, 0005→D7, 0004→D3, 0007→D8, 0008→D9, 0013→D10, 0015→D11, 0016→D12, hardener-row→D13 ✓
- Section C (uncaptured): B/0017→C2, F/0020→C5, J→C9, N/0018→C8+D14, O→C11, auto-permission/0019→C1, executing-fields→C7, retro-triage/0021→C10, self-url→D14 ✓
- Cross-cutting: observation-harness shared (D6 QA re-exec, D8 ux-engineer writes, D13 hardener honors); N=3 back-route (D3, carried by D8/D9); refute+surface QA merged across D6→D7; DESIGN.md reference-only (D8); curator chain order (D10) ✓

**Structure:** exactly two branches (`feat/fork-divergences-main`, `feat/fork-divergences-six-pack`), one PR each; per-divergence commits in a linear, dependency-correct order on each branch; no per-ADR branches, no four-pack PR.

**Within-branch ordering:** MAIN — only hard dep is C3 after C2; all `swarmforge.sh` commits are linear so no in-file conflict. SIX-PACK — D1<D3<D4<D5<D8<D9<D10 on specifier; D1<D2<D3<D6<D7<D9 on QA; D8<D9<D10 on conf; D10<D11 on workflow.

**Placeholder scan:** script edits show exact code; recovered files give exact `git show <branch>:<path>` + specific STRIP/FIX deltas; verification commands are concrete with expected output.

**Naming consistency:** `resolve_prompt_bundle`, `write_agent_instruction_file`, `write_worktree_advisor`, `write_worktree_permissions`, `ensure_skills_installed`, `install_skills` consistent across C2/C3/C4/C5/C8/C11; markers `.swarmforge/setup-complete` / `.swarmforge/skills-installed` consistent; conf window names match across D8/D9/D10.

**Known soft spots to confirm during execution (not blockers):**
- C2/C3/C4 line numbers drift — locate by function name.
- C7 executing-fields: find the actual executing-entry write site in the upstream handoff scripts (NOT a `swarmforge.sh` heredoc as on the cmux lineage).
- C6 QA holdout path (`qa-e2e`) must match the specifier-authored path — keep the one `QA_HOLDOUT_PATH` constant as the single source of truth.
- D10 curator: budgets are 60/40 from the locked spec, not 150/300.
