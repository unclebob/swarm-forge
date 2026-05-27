#!/usr/bin/env zsh
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: swarm-cleanup.sh <tmux-socket> <window-ids-file> [session ...]" >&2
  exit 1
fi

TMUX_SOCKET="$1"
WINDOW_IDS_FILE="$2"
shift
shift

for session in "$@"; do
  tmux -S "$TMUX_SOCKET" kill-session -t "$session" 2>/dev/null || true
done

sleep 1

if [[ ! -f "$WINDOW_IDS_FILE" ]]; then
  exit 0
fi

if [[ "${TERM_PROGRAM:-}" == "ghostty" ]]; then
  while IFS= read -r tab_id; do
    [[ -n "$tab_id" ]] || continue
    osascript - "$tab_id" <<'APPLESCRIPT' >/dev/null 2>&1 || true
on run argv
  set targetId to item 1 of argv
  tell application "Ghostty"
    try
      repeat with w in windows
        repeat with t in tabs of w
          if (id of t as string) is targetId then
            close tab t
            return
          end if
        end repeat
      end repeat
    end try
  end tell
end run
APPLESCRIPT
  done < "$WINDOW_IDS_FILE"
  exit 0
fi

while IFS= read -r window_id; do
  [[ -n "$window_id" ]] || continue
  osascript \
    -e 'tell application "Terminal"' \
    -e 'try' \
    -e 'close (first window whose id is '"$window_id"') saving no' \
    -e 'end try' \
    -e 'end tell' >/dev/null 2>&1 || true
done < "$WINDOW_IDS_FILE"
