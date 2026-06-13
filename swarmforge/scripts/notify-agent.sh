#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  echo "Usage: notify-agent.sh send <target-role> --file <body-file> [--sender <sender-role>] [--priority NN]" >&2
  echo "       notify-agent.sh receive --file <message-file> [--receiver <receiver-role>]" >&2
  echo "       notify-agent.sh complete --file <accepted-queue-file>" >&2
  echo "       notify-agent.sh <target-role-or-index> --file <message-file>" >&2
}

if [[ $# -gt 0 ]]; then
  case "$1" in
    send)
      shift
      exec "$SCRIPT_DIR/send-handoff.sh" "$@"
      ;;
    receive)
      shift
      exec "$SCRIPT_DIR/receive-handoff.sh" "$@"
      ;;
    resend)
      shift
      exec "$SCRIPT_DIR/resend-handoff.sh" "$@"
      ;;
    complete)
      shift
      exec "$SCRIPT_DIR/complete-handoff.sh" "$@"
      ;;
  esac
fi

if [[ $# -ne 3 || "${2:-}" != "--file" ]]; then
  usage
  exit 1
fi

TARGET="$1"
MESSAGE_FILE="$3"

find_project_dir() {
  local git_common_dir worktree_root

  if worktree_root=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null); then
    if [[ -f "$worktree_root/.swarmforge/sessions.tsv" && -f "$worktree_root/.swarmforge/delivery-outbox" ]]; then
      echo "$worktree_root"
      return 0
    fi
  fi

  if git_common_dir=$(git -C "$SCRIPT_DIR" rev-parse --git-common-dir 2>/dev/null); then
    if [[ "$git_common_dir" != /* ]]; then
      git_common_dir="$(cd "$SCRIPT_DIR/$git_common_dir" && pwd)"
    fi
    local project_dir="${git_common_dir:h}"
    if [[ -f "$project_dir/.swarmforge/sessions.tsv" ]]; then
      echo "$project_dir"
      return 0
    fi
  fi

  echo "${SCRIPT_DIR:h:h}"
}

PROJECT_DIR="$(find_project_dir)"
SESSIONS_FILE="$PROJECT_DIR/.swarmforge/sessions.tsv"
DELIVERY_OUTBOX_FILE="$PROJECT_DIR/.swarmforge/delivery-outbox"

if [[ ! -f "$SESSIONS_FILE" ]]; then
  echo "Sessions file not found: $SESSIONS_FILE" >&2
  exit 1
fi

resolve_session() {
  local target="${1:l}"
  local index role session display agent

  while IFS=$'\t' read -r index role session display agent; do
    if [[ "$target" == "${index:l}" || "$target" == "${role:l}" ]]; then
      echo "$session"
      return 0
    fi
  done < "$SESSIONS_FILE"

  return 1
}

TARGET_SESSION=$(resolve_session "$TARGET") || {
  echo "Unknown target: $TARGET" >&2
  exit 1
}

if [[ ! -f "$MESSAGE_FILE" ]]; then
  echo "Message file not found: $MESSAGE_FILE" >&2
  exit 1
fi
MESSAGE="$(< "$MESSAGE_FILE")"

if [[ -f "$DELIVERY_OUTBOX_FILE" ]]; then
  DELIVERY_OUTBOX="$(< "$DELIVERY_OUTBOX_FILE")"
else
  DELIVERY_OUTBOX="$PROJECT_DIR/.swarmforge/handoffs/outbox"
fi

mkdir -p "$DELIVERY_OUTBOX"

DELIVERY_ID="$(date '+%Y%m%d-%H%M%S')-$$-$RANDOM"
TMP_DELIVERY="$DELIVERY_OUTBOX/.$DELIVERY_ID.tmp"
READY_DELIVERY="$DELIVERY_OUTBOX/$DELIVERY_ID.ready"

{
  printf 'target session: %s\n' "$TARGET_SESSION"
  printf 'target role: %s\n' "$TARGET"
  printf '\n'
  printf '%s' "$MESSAGE"
} > "$TMP_DELIVERY"

mv "$TMP_DELIVERY" "$READY_DELIVERY"
echo "QUEUED $READY_DELIVERY"
