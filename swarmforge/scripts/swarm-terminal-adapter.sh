#!/usr/bin/env zsh

TERMINAL_ADAPTERS_DIR="${SCRIPT_DIR:-$(cd "$(dirname "$0")" && pwd)}/terminal-adapters"

normalize_terminal_backend() {
  local backend="${1:l}"

  case "$backend" in
    iterm|iterm2|iterm.app)
      echo "iterm2"
      ;;
    terminal|terminal-app|terminal.app)
      echo "terminal-app"
      ;;
    windows|windows-terminal|wt)
      echo "windows-terminal"
      ;;
    none|current|fallback)
      echo "none"
      ;;
    linux|linux-terminal|gnome-terminal|konsole|xfce4-terminal|tilix|alacritty|kitty|foot|xterm|x-terminal-emulator)
      echo "linux-terminal"
      ;;
    *)
      echo "$backend"
      ;;
  esac
}

_linux_display_available() {
  [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]]
}

_linux_terminal_available() {
  _linux_display_available || return 1

  local candidate
  for candidate in gnome-terminal konsole xfce4-terminal tilix alacritty kitty foot xterm x-terminal-emulator; do
    has_command "$candidate" && return 0
  done
  return 1
}

detect_terminal_backend() {
  if [[ -n "${SWARMFORGE_TERMINAL:-}" ]]; then
    normalize_terminal_backend "$SWARMFORGE_TERMINAL"
    return
  fi

  if has_command osascript; then
    if [[ "${TERM_PROGRAM:-}" == "iTerm.app" ]]; then
      echo "iterm2"
      return
    fi
    echo "terminal-app"
    return
  fi

  if has_command wt.exe; then
    echo "windows-terminal"
    return
  fi

  if [[ "$(uname -s)" == "Linux" ]] && _linux_terminal_available; then
    echo "linux-terminal"
    return
  fi

  echo "none"
}

load_terminal_backend() {
  local backend="$1"
  local adapter_file="$TERMINAL_ADAPTERS_DIR/$backend.sh"

  if [[ ! -r "$adapter_file" ]]; then
    echo "Unknown terminal backend '$backend'. Expected adapter file: $adapter_file" >&2
    return 1
  fi

  source "$adapter_file"
}
