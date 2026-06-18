---
status: accepted
---

# Per-role advisor model in `swarmforge.conf`

Different roles benefit from different advisor models for in-editor suggestions. Upstream added generic `extra-cli-args` passthrough for per-role agent flags (model, effort, etc.), so those no longer need fork-specific handling. The one remaining fork addition is `advisor=X` — there is no `--advisor` CLI flag; it must be written into the worktree's `.claude/settings.local.json`.

**Syntax: `advisor=<model>` as an extra token on the window line.** It is intercepted before passthrough; all other extra tokens are forwarded to the agent CLI verbatim.

```conf
# model/effort are raw extra-args (upstream passthrough)
window specifier  claude specifier  task --model claude-opus-4-8 --effort xhigh advisor=claude-sonnet-4-6
window coder      claude coder      task --model claude-sonnet-4-6

# advisor only (no model override needed)
window architect  claude architect  advisor=claude-sonnet-4-6
```

| Token | Applies to | Effect |
|-------|-----------|--------|
| `advisor=<model>` | claude only | writes `advisorModel` into the worktree's `.claude/settings.local.json` — there is no `--advisor` CLI flag |

**Implementation:** `parse-config` strips `advisor=X` tokens from the trailing fields before building `extra-args`; the extracted value is stored as `:advisor` on the row. `write-worktree-settings!` in `fork.bb` writes it to settings.local.json at launch time.
