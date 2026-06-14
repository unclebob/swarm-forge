#!/usr/bin/env zsh

terminal_backend_label() {
  echo "iTerm2"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_window_exists() {
  local session_id="$1"
  [[ -n "$session_id" ]] || return 1

  local result
  result="$(osascript - "$session_id" <<'APPLESCRIPT' 2>/dev/null || true
on run argv
  set targetId to item 1 of argv
  tell application id "com.googlecode.iterm2"
    repeat with w in windows
      repeat with t in tabs of w
        repeat with s in sessions of t
          if (id of s) is targetId then return "yes"
        end repeat
      end repeat
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
  set attachCmd to "cd " & quoted form of workingDir & " && exec tmux -S " & quoted form of tmuxSocket & " attach-session -t " & quoted form of tmuxSession

  tell application id "com.googlecode.iterm2"
    activate
    set newWindow to (create window with default profile)
    set newSession to current session of newWindow
    tell newSession to write text attachCmd
    try
      set name of newSession to windowTitle
    end try
    return id of newSession
  end tell
end run
APPLESCRIPT
}

terminal_close_window() {
  local session_id="$1"
  [[ -n "$session_id" ]] || return 0

  osascript - "$session_id" <<'APPLESCRIPT' >/dev/null 2>&1 || true
on run argv
  set targetId to item 1 of argv
  tell application id "com.googlecode.iterm2"
    repeat with w in windows
      repeat with t in tabs of w
        repeat with s in sessions of t
          if (id of s) is targetId then
            close w
            return
          end if
        end repeat
      end repeat
    end repeat
  end tell
end run
APPLESCRIPT
}
