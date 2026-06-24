---
name: setup-swarm
description: One-time project setup for SwarmForge. Run before the first `./swarm` launch. Installs language-appropriate quality tools, wires session tracking, writes permission allow-rules, and scaffolds .gitignore. Triggers on "setup swarm", "setup the swarm", "/setup-swarm", "first time setup", or "prepare project for swarm".
compatibility: Requires git, Python 3. Optional but recommended: entire CLI (0.6.2+) for session tracking.
metadata:
  author: gabadi/swarm-forge
  version: "0.1.0"
---

# setup-swarm

Run this skill **once** before invoking `./swarm`. It prepares the project so the swarm can operate without interruption. If you need to re-run setup, delete `.swarmforge/setup-complete` first.

---

## Step 1 — Ask the operator for the project stack

Read `swarmforge/constitution/articles/engineering.prompt` and extract the stacks listed under "Language tool table". Present only those stacks as numbered options — do not offer stacks that are not in that table.

Ask the operator:

> Which stack is this project?
> (list the stacks found in engineering.prompt, numbered)

Wait for the operator's answer before proceeding. Do not infer or detect the stack from the repository.

Once the operator answers, stamp the chosen language into the local engineering article so all agents know the project language. Append to `swarmforge/constitution/articles/local-engineering.prompt`:
```bash
printf '\n## Project Language\n- Project language: <chosen-language>.\n' >> swarmforge/constitution/articles/local-engineering.prompt
```
Where `<chosen-language>` is the language name exactly as it appears in the engineering.prompt tool table entry.

---

## Step 2 — Install quality tools

Read the "Language tool table" section of `swarmforge/constitution/articles/engineering.prompt`. For the chosen stack, install the mutation, CRAP, and DRY tools listed there — use the exact repositories and install method specified in that table.

Also install the Acceptance Pipeline Specification (APS) tools:
```
git clone https://github.com/unclebob/Acceptance-Pipeline-Specification /tmp/aps-build
cd /tmp/aps-build && go build -o gherkin-parser ./cmd/gherkin-parser && go build -o gherkin-mutator ./cmd/gherkin-mutator
cp gherkin-parser gherkin-mutator /usr/local/bin/ 2>/dev/null || cp gherkin-parser gherkin-mutator ~/.local/bin/
```
Warn and continue if the build fails (APS tools are quality-of-life, not blocking).

---

## Step 3 — Session tracking with entire

```bash
entire enable --no-github --telemetry=false
```

Then, for each unique backend listed in `swarmforge/swarmforge.conf` column 3 (e.g. `claude`, `codex`, `copilot`, `grok`):
```bash
entire agent add <backend>
```

If `entire` is not installed: print a warning ("entire not found — session tracking skipped") and continue. Setup never blocks on this.

---

## Step 4 — Permission allow-rules

Write minimal allow-rules to `.claude/settings.json` so the integrator and specifier can run their necessary git/gh commands unattended. Read the current file first (create `{}` if absent), merge in these two rules, and write it back:

```json
{
  "permissions": {
    "allow": [
      "Bash(gh pr merge*)",
      "Bash(git reset --hard*)"
    ]
  }
}
```

Use Python to merge (preserve any existing `allow` entries):
```python
import json, pathlib
p = pathlib.Path('.claude/settings.json')
cfg = json.loads(p.read_text()) if p.exists() else {}
cfg.setdefault('permissions', {}).setdefault('allow', [])
for rule in ['Bash(gh pr merge*)', 'Bash(git reset --hard*)']:
    if rule not in cfg['permissions']['allow']:
        cfg['permissions']['allow'].append(rule)
p.parent.mkdir(exist_ok=True)
p.write_text(json.dumps(cfg, indent=2))
```

---

## Step 5 — Scaffold .gitignore and probe default branch

Ensure these entries exist in `.gitignore` (append if missing, do not duplicate):
```
.swarmforge/
.worktrees/
tmp/
.claude/skills/swarm-persona/
```

Probe the repository's default remote branch:
```bash
git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's|refs/remotes/origin/||'
```

If this resolves to a branch name (e.g. `main`, `master`), record it:
```bash
mkdir -p .swarmforge
echo "<branch-name>" > .swarmforge/default-branch
```
This file lets the specifier reset to origin's default branch without hard-coding the name.

---

## Step 6 — Emit the swarm-ready marker

```bash
mkdir -p .swarmforge
printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(git rev-parse HEAD 2>/dev/null || echo 'no-git')" > .swarmforge/setup-complete
```

Print: `SwarmForge setup complete. Run ./swarm to start the session.`

The marker's presence is the signal to `./swarm` that the project is ready. If you need to re-run setup, delete this file.
