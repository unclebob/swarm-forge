#!/usr/bin/env zsh

terminal_backend_label() {
  echo "current shell"
}

terminal_backend_can_open_sessions() {
  return 1
}

terminal_backend_tracks_windows() {
  return 1
}

terminal_window_exists() {
  return 1
}

terminal_open_session() {
  return 1
}

terminal_close_window() {
  return 0
}
