#!/usr/bin/env zsh

terminal_backend_label() {
  echo "Ghostty"
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
  set targetId to item 1 of argv
  tell application "Ghostty"
    repeat with w in windows
      repeat with t in tabs of w
        if (id of t as string) is targetId then return "yes"
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
  local sibling_id="${3:-}"

  osascript - "$WORKING_DIR" "$session" "$title" "$TMUX_SOCKET" "$sibling_id" <<'APPLESCRIPT'
on run argv
  set workingDir to item 1 of argv
  set tmuxSession to item 2 of argv
  set tmuxSocket to item 4 of argv
  set siblingTabId to item 5 of argv
  set initialCmd to "cd " & quoted form of workingDir & " && exec tmux -S " & quoted form of tmuxSocket & " attach-session -t " & quoted form of tmuxSession & linefeed

  tell application "Ghostty"
    set cfg to new surface configuration
    set initial working directory of cfg to workingDir
    set initial input of cfg to initialCmd

    if siblingTabId is not "" then
      set targetWin to missing value
      set siblingTab to missing value
      repeat with w in windows
        repeat with t in tabs of w
          if (id of t as string) is siblingTabId then
            set targetWin to w
            set siblingTab to t
            exit repeat
          end if
        end repeat
        if targetWin is not missing value then exit repeat
      end repeat
      if targetWin is not missing value then
        select tab siblingTab
        set newTab to new tab in targetWin with configuration cfg
        return id of newTab
      end if
    end if

    try
      set targetWin to front window
      set newTab to new tab in targetWin with configuration cfg
      return id of newTab
    end try

    set newWin to new window with configuration cfg
    return id of (first tab of newWin)
  end tell
end run
APPLESCRIPT
}

terminal_close_window() {
  local window_id="$1"
  [[ -n "$window_id" ]] || return 0

  osascript - "$window_id" <<'APPLESCRIPT' >/dev/null 2>&1 || true
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
}
