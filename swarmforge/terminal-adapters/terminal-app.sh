#!/usr/bin/env zsh

terminal_backend_label() {
  echo "Terminal"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_window_exists() {
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

terminal_open_session() {
  local session="$1"
  local title="$2"

  osascript - "$WORKING_DIR" "$session" "$title" "$TMUX_SOCKET" <<'APPLESCRIPT'
on run argv
  set workingDir to item 1 of argv
  set tmuxSession to item 2 of argv
  set windowTitle to item 3 of argv
  set tmuxSocket to item 4 of argv

  tell application "Terminal"
    activate
    set newTab to do script ""
    do script "cd " & quoted form of workingDir & " && exec tmux -S " & quoted form of tmuxSocket & " attach-session -t " & quoted form of tmuxSession in newTab
    set custom title of newTab to windowTitle
    return id of front window
  end tell
end run
APPLESCRIPT
}

terminal_close_window() {
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
