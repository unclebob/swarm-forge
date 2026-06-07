#!/usr/bin/env zsh
set -euo pipefail

# cmux multiplexer teardown: close the whole role workspace group (which closes
# every workspace inside it), then close any recorded refs that were left
# ungrouped. cmux owns these workspaces; no tmux/window cleanup applies.
if [[ "${1:-}" == "--mux" ]]; then
  shift
  backend="${1:-}"
  shift
  if [[ "$backend" == "cmux" ]]; then
    group=""
    if [[ "${1:-}" == "--group" ]]; then
      shift
      group="${1:-}"
      shift
    fi
    if [[ -n "$group" ]]; then
      cmux workspace-group delete "$group" >/dev/null 2>&1 || true
    fi
    for ws in "$@"; do
      [[ -n "$ws" ]] || continue
      cmux workspace close "$ws" >/dev/null 2>&1 || true
    done
    exit 0
  fi
  echo "Unknown --mux backend: $backend" >&2
  exit 1
fi

if [[ $# -lt 2 ]]; then
  echo "Usage: swarm-cleanup.sh <tmux-socket> <window-ids-file> [session ...]" >&2
  echo "       swarm-cleanup.sh --mux cmux --group <group> [workspace ...]" >&2
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
