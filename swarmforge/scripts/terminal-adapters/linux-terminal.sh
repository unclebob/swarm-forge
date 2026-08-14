#!/usr/bin/env zsh
#
# Generic adapter for Linux desktop terminal emulators (X11 or Wayland).
# Unlike the macOS adapters, there is no OS-wide IPC (AppleScript) to query
# or close a specific window across every terminal emulator, so this backend
# does not track windows: sessions are opened detached and left for the user
# (or the tmux session ending) to close.

_linux_terminal_candidates=(gnome-terminal konsole xfce4-terminal tilix alacritty kitty foot xterm x-terminal-emulator)

_linux_has_command() {
  command -v "$1" &>/dev/null
}

_linux_pick_terminal() {
  if [[ -n "${SWARMFORGE_LINUX_TERMINAL:-}" ]] && _linux_has_command "$SWARMFORGE_LINUX_TERMINAL"; then
    echo "$SWARMFORGE_LINUX_TERMINAL"
    return 0
  fi

  local candidate
  for candidate in "${_linux_terminal_candidates[@]}"; do
    if _linux_has_command "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done

  return 1
}

terminal_backend_label() {
  _linux_pick_terminal 2>/dev/null || echo "Linux terminal"
}

terminal_backend_can_open_sessions() {
  _linux_pick_terminal >/dev/null 2>&1
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
  local terminal
  terminal="$(_linux_pick_terminal)" || return 1

  local attach_cmd="cd $(printf '%q' "$WORKING_DIR") && exec tmux -S $(printf '%q' "$TMUX_SOCKET") attach-session -t $(printf '%q' "$session")"
  local -a cmd

  case "$terminal" in
    gnome-terminal)
      cmd=(gnome-terminal "--title=$title" -- bash -lc "$attach_cmd")
      ;;
    konsole)
      cmd=(konsole -p "tabtitle=$title" -e bash -lc "$attach_cmd")
      ;;
    xfce4-terminal)
      cmd=(xfce4-terminal "--title=$title" -x bash -lc "$attach_cmd")
      ;;
    tilix)
      cmd=(tilix "--title=$title" -e bash -lc "$attach_cmd")
      ;;
    alacritty)
      cmd=(alacritty --title "$title" -e bash -lc "$attach_cmd")
      ;;
    kitty)
      cmd=(kitty --title "$title" bash -lc "$attach_cmd")
      ;;
    foot)
      cmd=(foot --title "$title" bash -lc "$attach_cmd")
      ;;
    xterm|x-terminal-emulator)
      cmd=("$terminal" -T "$title" -e bash -lc "$attach_cmd")
      ;;
    *)
      return 1
      ;;
  esac

  setsid "${cmd[@]}" >/dev/null 2>&1 &
  disown 2>/dev/null || true
  echo ""
}

terminal_close_window() {
  return 0
}
