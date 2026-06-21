#!/usr/bin/env bash
set -euo pipefail

# Merge sender's registered branch into the current worktree.
# Called by agents when a git_handoff payload arrives.
# Usage: merge_and_process <sender-role> <canonical-commit>
# Never uses --theirs or --ours; on conflict: stops and reports.

SENDER_ROLE="${1?Usage: merge_and_process <sender-role> <canonical-commit>}"
CANONICAL_COMMIT="${2?Usage: merge_and_process <sender-role> <canonical-commit>}"

echo "merge_and_process: merging ${CANONICAL_COMMIT} (from ${SENDER_ROLE})..."
if ! git merge --no-ff "${CANONICAL_COMMIT}"; then
  echo "" >&2
  echo "CONFLICT: merge of ${CANONICAL_COMMIT} into $(git symbolic-ref --short HEAD) failed." >&2
  echo "Resolve conflicts manually (do NOT use --theirs or --ours), then re-run ready_for_next.sh." >&2
  exit 1
fi

echo "merge_and_process: done — $(git rev-parse --short=10 HEAD)"
