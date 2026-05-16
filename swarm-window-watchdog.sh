#!/usr/bin/env zsh
set -euo pipefail

WINDOW_STATE_FILE="$1"
WINDOW_IDS_FILE="$2"
CLEANUP_OWNER_INDEX="$3"
WORKING_DIR="$4"
MISSING_THRESHOLD=3

typeset -A MISSING_COUNTS=()

window_exists() {
  local window_id="$1"
  [[ -n "$window_id" ]] || return 1

  local result
  result="$(osascript - "$window_id" <<'APPLESCRIPT' 2>/dev/null || true
on run argv
  set targetId to item 1 of argv as integer
  tell application "Terminal"
    repeat with terminalWindow in windows
      if id of terminalWindow is targetId then return "yes"
    end repeat
  end tell
  return "no"
end run
APPLESCRIPT
)"

  [[ "$result" == "yes" ]]
}

open_terminal_window() {
  local session="$1"
  local title="$2"

  osascript - "$WORKING_DIR" "$session" "$title" <<'APPLESCRIPT'
on run argv
  set workingDir to item 1 of argv
  set tmuxSession to item 2 of argv
  set windowTitle to item 3 of argv

  tell application "Terminal"
    activate
    set newTab to do script ""
    do script "cd " & quoted form of workingDir & " && exec tmux attach-session -t " & quoted form of tmuxSession in newTab
    set custom title of newTab to windowTitle
    return id of front window
  end tell
end run
APPLESCRIPT
}

close_terminal_window() {
  local window_id="$1"
  [[ -n "$window_id" ]] || return 0

  osascript - "$window_id" <<'APPLESCRIPT' >/dev/null 2>&1 || true
on run argv
  set targetId to item 1 of argv as integer
  tell application "Terminal"
    try
      close (first window whose id is targetId) saving no
    end try
  end tell
end run
APPLESCRIPT
}

kill_all_sessions() {
  local index window_id session title

  while IFS=$'\t' read -r index window_id session title || [[ -n "${index:-}" ]]; do
    [[ -n "${session:-}" ]] || continue
    tmux kill-session -t "$session" 2>/dev/null || true
  done < "$WINDOW_STATE_FILE"

  while IFS=$'\t' read -r index window_id session title || [[ -n "${index:-}" ]]; do
    [[ -n "${window_id:-}" ]] || continue
    close_terminal_window "$window_id"
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

  if [[ -z "$cleanup_session" ]] || ! tmux has-session -t "$cleanup_session" 2>/dev/null; then
    exit 0
  fi

  if window_exists "$cleanup_window_id"; then
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
    tmux has-session -t "$session" 2>/dev/null || continue

    if window_exists "$window_id"; then
      MISSING_COUNTS[$index]=0
    else
      MISSING_COUNTS[$index]=$(( ${MISSING_COUNTS[$index]:-0} + 1 ))
      (( MISSING_COUNTS[$index] >= MISSING_THRESHOLD )) || continue
      new_window_id="$(open_terminal_window "$session" "$title")"
      rewrite_window_id "$index" "$new_window_id"
      MISSING_COUNTS[$index]=0
    fi
  done < "$WINDOW_STATE_FILE"

  sleep 2
done
