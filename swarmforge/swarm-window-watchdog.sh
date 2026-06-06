#!/usr/bin/env zsh
set -euo pipefail

WINDOW_STATE_FILE="$1"
WINDOW_IDS_FILE="$2"
CLEANUP_OWNER_INDEX="$3"
TMUX_SOCKET="$4"
WORKING_DIR="$5"
TERMINAL_BACKEND="${6:-terminal-app}"
MISSING_THRESHOLD=3
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

has_command() {
  command -v "$1" &>/dev/null
}

source "$SCRIPT_DIR/swarm-terminal-adapter.sh"
load_terminal_backend "$TERMINAL_BACKEND"

typeset -A MISSING_COUNTS=()

kill_all_sessions() {
  local index window_id session title

  while IFS=$'\t' read -r index window_id session title || [[ -n "${index:-}" ]]; do
    [[ -n "${session:-}" ]] || continue
    tmux -S "$TMUX_SOCKET" kill-session -t "$session" 2>/dev/null || true
  done < "$WINDOW_STATE_FILE"

  while IFS=$'\t' read -r index window_id session title || [[ -n "${index:-}" ]]; do
    [[ -n "${window_id:-}" ]] || continue
    terminal_close_window "$window_id"
  done < "$WINDOW_STATE_FILE"
}

rewrite_window_id() {
  local target_index="$1"
  local replacement_id="$2"
  local tmp_file="${WINDOW_STATE_FILE}.$$"
  local index window_id session title

  : > "$tmp_file"
  while IFS=$'\t' read -r index window_id session title || [[ -n "${index:-}" ]]; do
    if [[ "$index" == "$target_index" ]]; then
      window_id="$replacement_id"
    fi
    printf '%s\t%s\t%s\t%s\n' "$index" "$window_id" "$session" "$title" >> "$tmp_file"
  done < "$WINDOW_STATE_FILE"

  mv "$tmp_file" "$WINDOW_STATE_FILE"
  awk -F '\t' '{ print $2 }' "$WINDOW_STATE_FILE" > "$WINDOW_IDS_FILE"
}

while [[ -f "$WINDOW_STATE_FILE" ]]; do
  cleanup_session=""
  cleanup_window_id=""
  while IFS=$'\t' read -r index window_id session title || [[ -n "${index:-}" ]]; do
    if [[ "$index" == "$CLEANUP_OWNER_INDEX" ]]; then
      cleanup_session="$session"
      cleanup_window_id="$window_id"
      break
    fi
  done < "$WINDOW_STATE_FILE"

  if [[ -z "$cleanup_session" ]] || ! tmux -S "$TMUX_SOCKET" has-session -t "$cleanup_session" 2>/dev/null; then
    exit 0
  fi

  if terminal_window_exists "$cleanup_window_id"; then
    MISSING_COUNTS[$CLEANUP_OWNER_INDEX]=0
  else
    MISSING_COUNTS[$CLEANUP_OWNER_INDEX]=$(( ${MISSING_COUNTS[$CLEANUP_OWNER_INDEX]:-0} + 1 ))
    if (( MISSING_COUNTS[$CLEANUP_OWNER_INDEX] >= MISSING_THRESHOLD )); then
      kill_all_sessions
      exit 0
    fi
    sleep 2
    continue
  fi

  while IFS=$'\t' read -r index window_id session title || [[ -n "${index:-}" ]]; do
    [[ -n "${index:-}" ]] || continue
    [[ "$index" != "$CLEANUP_OWNER_INDEX" ]] || continue
    tmux -S "$TMUX_SOCKET" has-session -t "$session" 2>/dev/null || continue

    if terminal_window_exists "$window_id"; then
      MISSING_COUNTS[$index]=0
    else
      MISSING_COUNTS[$index]=$(( ${MISSING_COUNTS[$index]:-0} + 1 ))
      (( MISSING_COUNTS[$index] >= MISSING_THRESHOLD )) || continue
      new_window_id="$(terminal_open_session "$session" "$title" "$cleanup_window_id")"
      [[ -n "$new_window_id" ]] || continue
      rewrite_window_id "$index" "$new_window_id"
      MISSING_COUNTS[$index]=0
    fi
  done < "$WINDOW_STATE_FILE"

  sleep 2
done
