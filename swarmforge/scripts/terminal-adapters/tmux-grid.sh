#!/usr/bin/env zsh
# tmux grid backend — one surface, one tile per role, on every platform.
#
# The other backends open one window per role. That is the right default: each window is
# independently trackable and the window watchdog can reopen one a role loses. It also means a
# six-pack puts six windows on the screen, stacked by the window manager, with no single surface
# that is the swarm.
#
# This backend tiles the roles instead:
#
#   +---------------+---------------+
#   |   Specifier   |     Coder     |
#   +---------------+---------------+
#   |    Cleaner    |   Architect   |
#   +---------------+---------------+
#   |   Hardender   |      QA       |
#   +---------------+---------------+
#
#   SWARMFORGE_TERMINAL=tmux-grid ./swarm
#
# The tiling is done by tmux, not by a terminal emulator, so it behaves the same under macOS
# Terminal.app or iTerm2, Windows Terminal from WSL, and any Linux terminal — including no
# terminal automation at all, where the grid is simply attached in the current shell.
#
# HOW IT PRESERVES THE ROLE SESSIONS
# Each role keeps its own tmux session, because handoff delivery targets a bare session name
# (`tmux send-keys -t swarmforge-<role>`), which tmux resolves to that session's current window's
# active pane. `join-pane` would move a role's only pane into another session's window, destroying
# the role's session and therefore its delivery address. So this backend does not move panes.
# It creates one extra viewer session whose panes each run `tmux attach-session -t <role>`. Every
# role session stays exactly as it was — one session, one window, one pane — and the daemon, the
# handoff routing and CURRENT_TASK never learn that the presentation changed.
#
# The viewer session is disposable: it holds no state, and when the role sessions are killed at
# cleanup its attach processes exit and the viewer session ends on its own.
#
# LAYOUT
# `select-layout tiled` decides the tiles, so the grid stays even as roles are added and no
# geometry is reimplemented here: a six-pack tiles 2x3, a four-pack 2x2, a two-pack side by side.
# Pane borders carry the project and the role name.
#
# THE OS WINDOW
# One window is opened for the viewer session by delegating to a normal single-window backend,
# chosen by SWARMFORGE_GRID_TERMINAL, or auto-detected the same way SwarmForge detects a backend
# when SWARMFORGE_TERMINAL is unset. If that resolves to `none` — a Linux box with no terminal
# automation — no window is opened and the grid is attached with:
#
#   tmux -S "$(cat .swarmforge/tmux-socket)" attach-session -t swarmforge-grid
#
# WINDOW BOOKKEEPING
# `terminal_backend_tracks_windows` is deliberately false. `.swarmforge/window-ids`, `windows.tsv`
# and `swarm-window-watchdog.sh` exist to reopen a whole window that one role lost, and they
# assume one window per role; in a shared grid there is no honest answer to "reopen role 4's
# window". So this backend opts out, and swarmforge.bb takes its own already-existing branch: no
# ids file, no watchdog, and it says so at startup. The tmux sessions remain the durable thing —
# a closed grid is re-attachable with the command above, and a single role with
# `tmux -S <socket> attach-session -t swarmforge-<role>`.

GRID_VIEWER_SESSION="${SWARMFORGE_GRID_SESSION:-swarmforge-grid}"
GRID_VIEWER_WINDOW="swarm"
GRID_VIEWER_COLUMNS="${SWARMFORGE_GRID_COLUMNS:-240}"
GRID_VIEWER_ROWS="${SWARMFORGE_GRID_ROWS:-60}"

terminal_backend_label() {
  echo "tmux grid"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 1
}

_grid_sessions_file() {
  echo "$WORKING_DIR/.swarmforge/sessions.tsv"
}

# Which swarm is this? Every project names its roles `swarmforge-<role>`, so two swarms running at
# once are indistinguishable — both read `[swarmforge-coder] Coder`. The label disambiguates them.
# Precedence: SWARMFORGE_LABEL for a single run, `.swarmforge/label` for a name that stays with the
# project, otherwise the working directory name, so tiles are never ambiguous with no setup.
_grid_label() {
  if [[ -n "${SWARMFORGE_LABEL:-}" ]]; then
    print -r -- "$SWARMFORGE_LABEL"
    return
  fi
  local label_file="$WORKING_DIR/.swarmforge/label" line
  if [[ -s "$label_file" ]]; then
    read -r line < "$label_file"
    if [[ -n "$line" ]]; then
      print -r -- "$line"
      return
    fi
  fi
  print -r -- "${WORKING_DIR:t}"
}

# TMUX is set for anything tmux spawns, and tmux refuses to attach from inside itself. Clearing it
# for the attach command is what allows a pane of the viewer session to hold a role session.
_grid_attach_command() {
  local session="$1"
  print -r -- "TMUX= exec tmux -S ${(q)TMUX_SOCKET} attach-session -t ${(q)session}"
}

# _grid_build — create the viewer session with one tiled pane per configured role.
_grid_build() {
  local label first=1 index=0 role_session display
  label="$(_grid_label)"

  while IFS=$'\t' read -r _ _ role_session display _; do
    [[ -n "$role_session" ]] || continue
    if (( first )); then
      # A detached session would default to 80x24, and attaching a role into a 39x6 pane makes the
      # agent redraw its whole UI into it. Start roomy; tmux shrinks to the client that attaches.
      tmux -S "$TMUX_SOCKET" new-session -d -s "$GRID_VIEWER_SESSION" -n "$GRID_VIEWER_WINDOW" \
        -x "$GRID_VIEWER_COLUMNS" -y "$GRID_VIEWER_ROWS" \
        "$(_grid_attach_command "$role_session")" || return 1
      first=0
    else
      tmux -S "$TMUX_SOCKET" split-window -t "$GRID_VIEWER_SESSION:$GRID_VIEWER_WINDOW" \
        "$(_grid_attach_command "$role_session")" || return 1
    fi
    # Re-tile after every split so no pane is ever too small for tmux to split again.
    tmux -S "$TMUX_SOCKET" select-layout -t "$GRID_VIEWER_SESSION:$GRID_VIEWER_WINDOW" tiled >/dev/null
    tmux -S "$TMUX_SOCKET" select-pane \
      -t "$GRID_VIEWER_SESSION:$GRID_VIEWER_WINDOW.$index" -T "$label | ${display:-$role_session}" >/dev/null
    (( index++ ))
  done < "$(_grid_sessions_file)"

  (( index > 0 )) || return 1

  # The pane border is the only per-tile label every terminal renders identically.
  tmux -S "$TMUX_SOCKET" set-option -t "$GRID_VIEWER_SESSION" pane-border-status top >/dev/null
  tmux -S "$TMUX_SOCKET" set-option -t "$GRID_VIEWER_SESSION" pane-border-format " #{pane_title} " >/dev/null
  tmux -S "$TMUX_SOCKET" set-option -t "$GRID_VIEWER_SESSION" status-left "[$label] " >/dev/null
  tmux -S "$TMUX_SOCKET" set-option -t "$GRID_VIEWER_SESSION" status-left-length $(( ${#label} + 4 )) >/dev/null
  tmux -S "$TMUX_SOCKET" select-pane -t "$GRID_VIEWER_SESSION:$GRID_VIEWER_WINDOW.0" >/dev/null
}

_grid_viewer_exists() {
  tmux -S "$TMUX_SOCKET" has-session -t "$GRID_VIEWER_SESSION" &>/dev/null
}

# Which single-window backend opens the one window? Explicit choice first, then the same detection
# SwarmForge uses when SWARMFORGE_TERMINAL is unset. `tmux-grid` itself is never a valid answer.
_grid_inner_backend() {
  local inner="${SWARMFORGE_GRID_TERMINAL:-}"
  if [[ -z "$inner" ]]; then
    inner="$(SWARMFORGE_TERMINAL= detect_terminal_backend)"
  fi
  inner="$(normalize_terminal_backend "$inner")"
  case "$inner" in
    tmux-grid|grid|"") echo "none" ;;
    *) echo "$inner" ;;
  esac
}

# Delegate to the inner backend in a child shell. Sourcing it here would overwrite the very
# functions being executed, so the inner adapter is only ever loaded in a separate process.
_grid_delegate() {
  local inner="$1"
  shift
  zsh -c "
    SCRIPT_DIR=${(q)SCRIPT_DIR}
    WORKING_DIR=${(q)WORKING_DIR}
    TMUX_SOCKET=${(q)TMUX_SOCKET}
    has_command() { command -v \"\$1\" &>/dev/null; }
    source ${(q)SCRIPT_DIR}/swarm-terminal-adapter.sh
    load_terminal_backend ${(q)inner} || exit 1
    ${(j: :)${(q)@}}
  "
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local inner

  # The whole grid is built once, by whichever role arrives first; the rest are already in it.
  if _grid_viewer_exists; then
    return 0
  fi
  _grid_build || return 1

  inner="$(_grid_inner_backend)"
  [[ "$inner" != "none" ]] || return 0
  _grid_delegate "$inner" terminal_backend_can_open_sessions || return 0
  _grid_delegate "$inner" terminal_open_session "$GRID_VIEWER_SESSION" "$(_grid_label) swarm"
}

terminal_window_exists() {
  # No per-role window is tracked, so there is nothing for the watchdog to find.
  return 1
}

terminal_close_window() {
  local window_id="$1"
  local inner
  [[ -n "$window_id" ]] || return 0
  inner="$(_grid_inner_backend)"
  [[ "$inner" != "none" ]] || return 0
  _grid_delegate "$inner" terminal_close_window "$window_id"
}
