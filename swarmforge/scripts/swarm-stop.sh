#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/handoff-lib.sh"

_swarm_stop_main() {
  local role="${SWARMFORGE_ROLE:-}"
  if [[ -z "$role" ]]; then
    return 0
  fi

  local stdin_json=""
  if [[ ! -t 0 ]]; then
    stdin_json="$(cat)"
  fi

  local cwd=""
  if [[ -n "$stdin_json" ]]; then
    cwd="$(printf '%s' "$stdin_json" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("cwd",""))' 2>/dev/null || true)"
  fi

  local project_dir=""
  if [[ -n "$cwd" && -d "$cwd" ]]; then
    project_dir="$(handoff_project_dir_from "$cwd")"
  else
    project_dir="$(handoff_project_dir)"
  fi

  local pending_dir busy_file
  pending_dir="$(handoff_pending_dir "$project_dir" "$role")"
  busy_file="$(handoff_busy_file "$project_dir" "$role")"

  if [[ ! -d "$pending_dir" ]] || [[ -z "$(ls -A "$pending_dir" 2>/dev/null)" ]]; then
    rm -f "$busy_file"
    return 0
  fi

  local pending_name pending_file pending_content
  pending_name="$(ls "$pending_dir" | sort | head -1)"
  pending_file="$pending_dir/$pending_name"

  touch "$busy_file"

  "$SCRIPT_DIR/swarm-handoff" deliver "$role" --file "$pending_file"

  rm -f "$pending_file"
}

_swarm_stop_main
