# Hunk Agent Guide

How agents interact with a live Hunk session for code review.

## Pre-requisites

- Hunk must be running in a terminal (e.g. `git diff ... | hunk patch -`).
- The Hunk session daemon auto-registers on startup.

## Inspect

```bash
hunk session list
git diff upstream/main...HEAD --diff-filter=M | hunk patch -
```

## Inspect

```bash
hunk session list                              # find live sessions
hunk session get --repo .                    # confirm session repo match
hunk session review --repo . --json          # file/hunk structure
hunk session review --repo . --include-patch --json  # include raw diff text
hunk session context --repo .                # current focus
```

## Navigate

```bash
hunk session navigate --repo . --file <path> --hunk <n>     # 1-based hunk
hunk session navigate --repo . --file <path> --new-line <n>
hunk session navigate --repo . --next-comment
hunk session navigate --repo . --prev-comment
```

## Reload content

```bash
hunk session reload --repo . -- diff --exclude-untracked
hunk session reload --repo . -- show HEAD~1
```

Always pass `--` before the nested Hunk command.

## Add comments

Single note:
```bash
hunk session comment add --repo . --file <path> --new-line <n> --summary "text" [--focus]
```

Batch:
```bash
printf '%s\n' '{"comments":[{"filePath":"...","newLine":N,"summary":"..."}]}' \
  | hunk session comment apply --repo . --stdin [--focus]
```

## Common fixes

- **"No active session matches repoRoot"** — pass session ID explicitly instead of `--repo .`.
- **"No active Hunk sessions"** — Hunk is not running; ask the user to open it first.
