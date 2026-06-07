#!/usr/bin/env zsh
set -euo pipefail

# Stops the swarm for a single project directory by reusing swarm-cleanup.sh:
# kills every tmux session on the project's dedicated socket and closes every
# tracked terminal window. State is read from <project>/.swarmforge/, written
# when the swarm was started.

WORKING_DIR="${1:-$PWD}"
WORKING_DIR="$(cd "$WORKING_DIR" && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STATE_DIR="$WORKING_DIR/.swarmforge"

if [[ ! -d "$STATE_DIR" ]]; then
  echo "No swarm found in $WORKING_DIR (missing .swarmforge/)." >&2
  exit 1
fi

SOCKET_FILE="$STATE_DIR/tmux-socket"
WINDOW_IDS_FILE="$STATE_DIR/window-ids"
SESSIONS_FILE="$STATE_DIR/sessions.tsv"

# Backend resolution: explicit env wins, else the value persisted at startup,
# else swarm-cleanup.sh's own default. This lets `swarm stop` close cmux
# workspaces without the caller re-passing SWARMFORGE_TERMINAL.
backend="${SWARMFORGE_TERMINAL:-}"
if [[ -z "$backend" && -f "$STATE_DIR/terminal-backend" ]]; then
  backend="$(<"$STATE_DIR/terminal-backend")"
fi

if [[ ! -f "$SOCKET_FILE" ]]; then
  echo "No tmux socket recorded for $WORKING_DIR; nothing to stop." >&2
  exit 1
fi
socket="$(<"$SOCKET_FILE")"

# Session names live in column 3 of sessions.tsv. Passing them all makes
# swarm-cleanup.sh kill every session; killing the cleanup-owner session also
# makes the watchdog exit on its next tick instead of reopening windows.
sessions=()
if [[ -f "$SESSIONS_FILE" ]]; then
  sessions=( ${(f)"$(cut -f3 "$SESSIONS_FILE")"} )
fi

echo "Stopping swarm in $WORKING_DIR (backend: ${backend:-default})..."
SWARMFORGE_TERMINAL_BACKEND="${backend:-terminal-app}" \
  "$SCRIPT_DIR/swarm-cleanup.sh" "$socket" "$WINDOW_IDS_FILE" "${sessions[@]}"

echo "Swarm stopped."
