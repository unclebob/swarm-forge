#!/usr/bin/env zsh
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: swarm-cleanup.sh <tmux-socket> <window-ids-file> [session ...]" >&2
  exit 1
fi

TMUX_SOCKET="$1"
WINDOW_IDS_FILE="$2"
TERMINAL_BACKEND="${SWARMFORGE_TERMINAL_BACKEND:-terminal-app}"
WORKING_DIR="$(cd "$(dirname "$WINDOW_IDS_FILE")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
shift
shift

has_command() {
  command -v "$1" &>/dev/null
}

source "$SCRIPT_DIR/swarm-terminal-adapter.sh"
load_terminal_backend "$TERMINAL_BACKEND"

for session in "$@"; do
  tmux -S "$TMUX_SOCKET" kill-session -t "$session" 2>/dev/null || true
done

sleep 1

if [[ -f "$WINDOW_IDS_FILE" ]]; then
  while IFS= read -r window_id; do
    [[ -n "$window_id" ]] || continue
    terminal_close_window "$window_id"
  done < "$WINDOW_IDS_FILE"
fi
