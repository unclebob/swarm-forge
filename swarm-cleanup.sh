#!/usr/bin/env zsh
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: swarm-cleanup.sh <window-ids-file> [session ...]" >&2
  exit 1
fi

WINDOW_IDS_FILE="$1"
shift

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# WINDOW_IDS_FILE lives at <WORKING_DIR>/.swarmforge/window-ids, so the
# working directory is two levels up.
WORKING_DIR="$(cd "$(dirname "$(dirname "$WINDOW_IDS_FILE")")" && pwd)"

if [[ -f "$SCRIPT_DIR/swarm-registry.sh" ]]; then
  source "$SCRIPT_DIR/swarm-registry.sh"
  registry_remove "$WORKING_DIR" || true
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
