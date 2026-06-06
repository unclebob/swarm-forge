#!/usr/bin/env zsh

terminal_backend_label() {
  echo "Windows Terminal"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 1
}

terminal_window_exists() {
  return 1
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local escaped_working_dir
  local escaped_tmux_socket
  local escaped_session

  escaped_working_dir="$(printf '%q' "$WORKING_DIR")"
  escaped_tmux_socket="$(printf '%q' "$TMUX_SOCKET")"
  escaped_session="$(printf '%q' "$session")"

  wt.exe -w new --title "$title" wsl.exe -e bash -lc \
    "cd $escaped_working_dir && exec tmux -S $escaped_tmux_socket attach-session -t $escaped_session"
}

terminal_close_window() {
  return 0
}
