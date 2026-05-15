#!/usr/bin/env zsh
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: swarm-cleanup.sh <working-dir> <instance-id> [session ...]" >&2
  exit 1
fi

WORKING_DIR="$1"
INSTANCE_ID="$2"
shift 2

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STATE_DIR="$WORKING_DIR/.swarmforge/instances/$INSTANCE_ID"
WINDOW_IDS_FILE="$STATE_DIR/window-ids"

if [[ -f "$SCRIPT_DIR/swarm-registry.sh" ]]; then
  source "$SCRIPT_DIR/swarm-registry.sh"
  registry_remove "$WORKING_DIR" "$INSTANCE_ID" || true
fi

for session in "$@"; do
  tmux kill-session -t "$session" 2>/dev/null || true
done

sleep 1

if [[ -f "$WINDOW_IDS_FILE" ]]; then
  while IFS= read -r window_id; do
    [[ -n "$window_id" ]] || continue
    osascript \
      -e 'tell application "Terminal"' \
      -e 'try' \
      -e 'close (first window whose id is '"$window_id"') saving no' \
      -e 'end try' \
      -e 'end tell' >/dev/null 2>&1 || true
  done < "$WINDOW_IDS_FILE"
fi
