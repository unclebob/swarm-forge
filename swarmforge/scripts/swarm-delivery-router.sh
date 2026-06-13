#!/usr/bin/env zsh
set -euo pipefail
setopt NULL_GLOB

if [[ $# -ne 3 ]]; then
  echo "Usage: swarm-delivery-router.sh <tmux-socket> <sessions-file> <delivery-outboxes-file>" >&2
  exit 1
fi

TMUX_SOCKET="$1"
SESSIONS_FILE="$2"
DELIVERY_OUTBOXES_FILE="$3"
POLL_SECONDS="${SWARMFORGE_DELIVERY_POLL_SECONDS:-1}"

tmux_window_base_index() {
  local index
  index="$(tmux -S "$TMUX_SOCKET" show-options -gqv base-index 2>/dev/null || echo 0)"
  if [[ "$index" == <-> ]]; then
    echo "$index"
  else
    echo 0
  fi
}

tmux_pane_base_index() {
  local index
  index="$(tmux -S "$TMUX_SOCKET" show-window-options -gqv pane-base-index 2>/dev/null || echo 0)"
  if [[ "$index" == <-> ]]; then
    echo "$index"
  else
    echo 0
  fi
}

any_session_alive() {
  local index role session display agent
  while IFS=$'\t' read -r index role session display agent || [[ -n "${index:-}" ]]; do
    [[ -n "${session:-}" ]] || continue
    if tmux -S "$TMUX_SOCKET" has-session -t "$session" 2>/dev/null; then
      return 0
    fi
  done < "$SESSIONS_FILE"
  return 1
}

delivery_field() {
  local field="$1"
  local file="$2"
  local line
  line="$(grep -m 1 -E "^${field}: " "$file" || true)"
  if [[ -z "$line" ]]; then
    return 1
  fi
  printf '%s' "${line#*: }"
}

delivery_body() {
  awk 'found { print } /^$/ { found = 1 }' "$1"
}

dispatch_delivery() {
  local delivery_file="$1"
  local outbox_dir="$2"
  local target_session target message delivered_dir failed_dir base window_index pane_index

  target_session="$(delivery_field "target session" "$delivery_file")" || return 1
  message="$(delivery_body "$delivery_file")"
  window_index="$(tmux_window_base_index)"
  pane_index="$(tmux_pane_base_index)"
  target="${target_session}:${window_index}.${pane_index}"

  tmux -S "$TMUX_SOCKET" send-keys -t "$target" -l -- "$message"
  sleep 0.15
  tmux -S "$TMUX_SOCKET" send-keys -t "$target" C-m
  sleep 0.05
  tmux -S "$TMUX_SOCKET" send-keys -t "$target" C-j

  delivered_dir="${outbox_dir:h}/delivered"
  mkdir -p "$delivered_dir"
  base="${delivery_file:t}"
  mv "$delivery_file" "$delivered_dir/$base"
}

while any_session_alive; do
  while IFS=$'\t' read -r role outbox_dir || [[ -n "${role:-}" ]]; do
    [[ -n "${outbox_dir:-}" ]] || continue
    mkdir -p "$outbox_dir"
    for delivery_file in "$outbox_dir"/*.ready; do
      dispatch_delivery "$delivery_file" "$outbox_dir" || {
        failed_dir="${outbox_dir:h}/failed"
        mkdir -p "$failed_dir"
        mv "$delivery_file" "$failed_dir/${delivery_file:t}" 2>/dev/null || true
      }
    done
  done < "$DELIVERY_OUTBOXES_FILE"
  sleep "$POLL_SECONDS"
done
