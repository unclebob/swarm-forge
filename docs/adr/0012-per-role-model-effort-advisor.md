---
status: accepted
---

# Per-role model, effort, and advisor in `swarmforge.conf`

Different roles have different compute needs — the architect reasoning about design warrants a more capable model than the coder grinding through an implementation slice. Upstream's only per-role knob is the agent backend (`window <role> <backend> <worktree>`); model, effort, and advisor are absent. The fork adds **optional per-role overrides** without breaking any existing config.

**Syntax: an inline `key=value` tail on the window line.** The existing four fields parse exactly as before; any fields beyond position four are read as `key=value` pairs stored per role. Upstream rejects lines that are not exactly four fields, so this is a genuine parser change, but it is backward compatible — a four-field line still works untouched.

```conf
# before (still valid)
window coder      claude coder

# after (opt-in per role)
window specifier  claude specifier  model=opus    effort=xhigh  advisor=sonnet
window coder      claude coder      model=sonnet  effort=high
window architect  codex  architect  model=o3
```

**Three keys, mapped to CLI flags per backend; unsupported keys are silently ignored:**

| Key | Applies to | Mapping |
|-----|-----------|---------|
| `model` | all backends | `claude`/`copilot`/`grok`: `--model <val>` · `codex`: `-c model="<val>"` |
| `effort` | claude, copilot, grok | `--effort <val>` (codex has no effort flag — skipped) |
| `advisor` | claude only | `--advisor <val>` (ignored for other backends) |

**Per-role granularity, not per-backend.** Two `claude` roles can run different models; a global per-backend setting would throw away the value of the role abstraction. **No pre-populated values** ship in the runnable configs — those express topology (roles + worktrees), not opinions about model cost. The feature is fully opt-in: operators add keys only to the lines they care about.

## Pending implementation

- `main`: extend `parse_config` in `swarmforge.sh` to accept ≥4 fields and read the `key=value` tail into per-role maps; extend `launch_role` to append the mapped flags per backend when set. (Script lives on `main`; the conf grammar is exercised there.)
- Verify each mapped flag actually exists on the target CLI before relying on it — in particular confirm `claude --advisor` is a real flag; if not, treat `advisor` as reserved-but-inert until the CLI supports it.
- Runnable configs (`four-pack`/`six-pack`) stay topology-only — no keys added.
