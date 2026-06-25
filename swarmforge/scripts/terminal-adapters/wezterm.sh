#!/usr/bin/env zsh

terminal_backend_label() {
  echo "WezTerm"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_window_exists() {
  local pane_id="$1"
  [[ -n "$pane_id" ]] || return 1

  local list
  list="$(wezterm cli list --format json 2>/dev/null)" || return 1

  if command -v jq &>/dev/null; then
    echo "$list" | jq -e --argjson id "$pane_id" 'any(.[]; .pane_id == $id)' &>/dev/null
  else
    echo "$list" | python3 -c "
import sys, json
data = json.load(sys.stdin)
sys.exit(0 if any(p['pane_id'] == $pane_id for p in data) else 1)
" 2>/dev/null
  fi
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"

  local spawn_args=()

  if [[ -n "$sibling_id" ]]; then
    local list window_id
    list="$(wezterm cli list --format json 2>/dev/null)"
    if command -v jq &>/dev/null; then
      window_id="$(echo "$list" | jq -r --argjson id "$sibling_id" \
        '.[] | select(.pane_id == $id) | .window_id')"
    else
      window_id="$(echo "$list" | python3 -c "
import sys, json
data = json.load(sys.stdin)
matches = [str(p['window_id']) for p in data if p['pane_id'] == $sibling_id]
if matches: print(matches[0])
" 2>/dev/null)"
    fi
    [[ -n "$window_id" ]] && spawn_args+=(--window-id "$window_id")
  fi

  local pane_id
  pane_id="$(wezterm cli spawn "${spawn_args[@]}" --cwd "$WORKING_DIR" \
    -- bash -lc "exec tmux -S $(printf '%q' "$TMUX_SOCKET") attach-session -t $(printf '%q' "$session")")"

  [[ -n "$pane_id" ]] && wezterm cli set-tab-title --pane-id "$pane_id" "$title" 2>/dev/null || true

  echo "$pane_id"
}

terminal_close_window() {
  local pane_id="$1"
  [[ -n "$pane_id" ]] || return 0

  wezterm cli kill-pane --pane-id "$pane_id" 2>/dev/null || true
}
