#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SESSIONS_FILE="$ROOT_DIR/.swarmforge/sessions.tsv"

if [[ $# -lt 2 ]]; then
  echo "Usage: ./notify-agent.sh <target-role-or-index> \"message\"" >&2
  exit 1
fi

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

TARGET_SESSION=$(resolve_session "$1") || {
  echo "Unknown target: $1" >&2
  exit 1
}

MESSAGE="${*:2}"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
mkdir -p "$ROOT_DIR/logs"
echo "[$TIMESTAMP] [$TARGET_SESSION] $MESSAGE" >> "$ROOT_DIR/logs/agent_messages.log"
tmux send-keys -t "${TARGET_SESSION}:0.0" -l -- "$MESSAGE"
sleep 0.15
tmux send-keys -t "${TARGET_SESSION}:0.0" C-m
sleep 0.05
tmux send-keys -t "${TARGET_SESSION}:0.0" C-j
