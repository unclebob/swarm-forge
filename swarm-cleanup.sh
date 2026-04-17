#!/usr/bin/env zsh
set -euo pipefail

WINDOW_IDS_FILE="$1"
shift

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
