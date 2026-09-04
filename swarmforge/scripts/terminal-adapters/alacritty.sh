#!/usr/bin/env bash
# swarmforge/scripts/terminal-adapters/alacritty.sh
#
# Launch-only Alacritty adapter for SwarmForge (macOS).
# Alacritty has no AppleScript dictionary and no reliable per-window id,
# so this follows the same "launch-only" contract as windows-terminal.sh:
#   - terminal_backend_can_open_sessions -> yes, we CAN open a surface
#   - terminal_backend_tracks_windows    -> no, we can't track/reopen it
#
# That means SwarmForge will open one Alacritty window per role and skip
# the watchdog (no auto-reopen if you close a window). Closing the first
# configured window still does the normal cleanup/shutdown, because that
# path doesn't depend on window tracking.

terminal_backend_label() {
  echo "Alacritty"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  # No stable window id to track -> launch-only, like windows-terminal.sh
  return 1
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"

  # $WORKING_DIR and $TMUX_SOCKET are provided by SwarmForge's environment.
  # We open a new Alacritty window whose shell attaches to the right
  # tmux socket/session. --title sets the window title so it's easy to
  # find manually if you ever need to.
  open -na "Alacritty" --args \
    --title "${title:-$session}" \
    -e /bin/zsh -lc "cd \"$WORKING_DIR\" && exec tmux -S \"$TMUX_SOCKET\" attach-session -t \"$session\""

  # No stable id available; print something so the caller has a value,
  # but terminal_window_exists/terminal_close_window below don't rely on it.
  echo "alacritty:$session"
}

terminal_window_exists() {
  local window_id="$1"
  # Launch-only backend: nothing to check, always report "gone" so
  # SwarmForge doesn't try to manage a window it can't track.
  return 1
}

terminal_close_window() {
  local window_id="$1"
  # Launch-only backend: nothing SwarmForge can reliably close here.
  # (You can Cmd+W the Alacritty window yourself, or close the tmux
  # session directly: tmux -S "$TMUX_SOCKET" kill-session -t "<session>")
  return 0
}
