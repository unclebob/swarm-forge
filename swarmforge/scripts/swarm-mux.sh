#!/usr/bin/env zsh
# swarm-mux.sh — pluggable terminal-multiplexer backend for SwarmForge.
#
# A multiplexer layer that sits ABOVE the terminal-adapter layer. swarmforge.sh
# sources this and guards every multiplexer call-site:
#   if mux_is_cmux; then mux_*; else <tmux code, unchanged>; fi
# so the tmux path stays exactly as it was and all cmux logic lives here.
#
# Unlike a tmux-under-cmux window adapter, the cmux backend here makes cmux the
# REAL multiplexer: there is no tmux. Each role gets its own native cmux
# workspace (the agent runs directly in the workspace shell), and all role
# workspaces are collected under one "SwarmForge · <project>" workspace group.
#
# Backend is selected by $SWARM_MUX (default: tmux). Relies on globals defined by
# swarmforge.sh: ROLES, SESSIONS, DISPLAY_NAMES, WORKTREE_PATHS, MUX_TARGETS,
# STATE_DIR, WORKING_DIR, and the write_sessions_file helper.

# Backend selection: an explicit $SWARM_MUX wins; otherwise auto-detect from the
# environment. cmux exports a family of CMUX_* vars into its terminals; if ANY of
# the reliable ones is present the launcher is inside cmux, so default to cmux.
# CMUX_SOCKET_PATH/CMUX_SOCKET are the load-bearing signals: they are set in every
# cmux-spawned shell (and CMUX_SOCKET_PATH is what activates the `tmux`->cmux shim
# that makes tmux-under-cmux unreliable), whereas CMUX_WORKSPACE_ID can be absent
# in some launch contexts. Else fall back to tmux, which bootstraps its own
# sessions and terminal windows.
if [[ -z "${SWARM_MUX:-}" ]]; then
  if [[ -n "${CMUX_SOCKET_PATH:-}" || -n "${CMUX_SOCKET:-}" || -n "${CMUX_WORKSPACE_ID:-}" || -n "${CMUX_SURFACE_ID:-}" ]]; then
    SWARM_MUX=cmux
  else
    SWARM_MUX=tmux
  fi
fi
CMUX_GROUP=""
CMUX_WORKSPACES_FILE="${STATE_DIR:-$PWD/.swarmforge}/cmux-workspaces"
CMUX_GROUP_FILE="${STATE_DIR:-$PWD/.swarmforge}/cmux-group"

mux_is_cmux() {
  [[ "$SWARM_MUX" == "cmux" ]]
}

# Binary that check_dependency must verify for the selected backend.
mux_dependency() {
  case "$SWARM_MUX" in
    tmux) echo tmux ;;
    cmux) echo cmux ;;
    *)
      echo "Error: unknown SWARM_MUX '$SWARM_MUX' (expected: tmux | cmux)" >&2
      exit 1
      ;;
  esac
}

# Extract the first whitespace-separated token with the given prefix (e.g.
# "workspace:" or "group:") from stdin. cmux prints lines like "OK workspace:3";
# this is tolerant of the leading "OK " and of any surrounding text.
_mux_extract_ref() {
  local prefix="$1"
  awk -v p="$prefix" '{ for (i = 1; i <= NF; i++) if (index($i, p) == 1) { print $i; exit } }'
}

# Seed MUX_TARGETS with placeholders so an early write_sessions_file is harmless.
# tmux: targets are the session names, known up front (final).
# cmux: overwritten with workspace refs by mux_create_all once they exist.
mux_init_targets() {
  local i
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    MUX_TARGETS[$i]="${SESSIONS[$i]}"
  done
}

# Close any role workspaces left over from a previous cmux run. Deleting the
# group closes every workspace still inside it; closing the recorded refs cleans
# up any that were ungrouped. Never touches workspaces we did not create.
mux_kill_existing() {
  local prev_group=""
  [[ -f "$CMUX_GROUP_FILE" ]] && prev_group="$(< "$CMUX_GROUP_FILE")"
  if [[ -n "$prev_group" ]]; then
    cmux workspace-group delete "$prev_group" >/dev/null 2>&1 || true
  fi
  if [[ -f "$CMUX_WORKSPACES_FILE" ]]; then
    local ws
    while IFS= read -r ws; do
      [[ -n "$ws" ]] || continue
      cmux workspace close "$ws" >/dev/null 2>&1 || true
    done < "$CMUX_WORKSPACES_FILE"
  fi
  : > "$CMUX_WORKSPACES_FILE"
  : > "$CMUX_GROUP_FILE"
}

# Create one native cmux workspace per role and collect them under a single
# "SwarmForge · <project>" group. The first role anchors a fresh group; each
# remaining role joins it. Records each workspace ref into MUX_TARGETS and the
# state files. The agent itself is launched later by mux_deliver.
mux_create_all() {
  : > "$CMUX_WORKSPACES_FILE"
  : > "$CMUX_GROUP_FILE"
  CMUX_GROUP=""
  local i ws
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    ws="$(cmux workspace create --name "SwarmForge ${DISPLAY_NAMES[$i]}" --cwd "${WORKTREE_PATHS[$i]}" --focus false 2>&1 | _mux_extract_ref 'workspace:')"
    if [[ "$ws" != workspace:* ]]; then
      echo "Error: cmux workspace create failed for role '${ROLES[$i]}' (got: ${ws:-<empty>})" >&2
      exit 1
    fi

    if [[ -z "$CMUX_GROUP" ]]; then
      # Name the group after the project directory so concurrent swarms in
      # different projects are distinguishable in the sidebar.
      CMUX_GROUP="$(cmux workspace-group create --name "SwarmForge · ${WORKING_DIR:t}" --from "$ws" 2>&1 | _mux_extract_ref 'group:')"
      printf '%s\n' "$CMUX_GROUP" > "$CMUX_GROUP_FILE"
    else
      cmux workspace-group add --group "$CMUX_GROUP" --workspace "$ws" >/dev/null 2>&1 || true
    fi

    MUX_TARGETS[$i]="$ws"
    echo "$ws" >> "$CMUX_WORKSPACES_FILE"
  done
  write_sessions_file
}

# Deliver a launch command to a role's workspace (text then Enter).
mux_deliver() {
  local index="$1"
  local cmd="$2"
  local ws="${MUX_TARGETS[$index]}"
  cmux send --workspace "$ws" -- "$cmd" >/dev/null
  sleep 0.15
  cmux send-key --workspace "$ws" enter >/dev/null
}

# Argument vector handed to swarm-cleanup.sh by the cleanup-owner's launch tail.
mux_cleanup_args() {
  local args="--mux cmux --group '${CMUX_GROUP}'" t
  for t in "${MUX_TARGETS[@]}"; do
    [[ -n "$t" ]] || continue
    args+=" '$t'"
  done
  echo "$args"
}

# Role workspaces are already created; bring the first one forward so the group
# is visible in the sidebar.
mux_open_views() {
  [[ -n "${MUX_TARGETS[1]:-}" ]] || return 0
  cmux workspace select "${MUX_TARGETS[1]}" >/dev/null 2>&1 || true
}

# Backend-specific tail appended to the generated notify-agent.sh. $TARGET_SESSION
# (a workspace ref for cmux) and $MESSAGE are defined by the surrounding script.
mux_notify_snippet() {
  cat <<'SNIPPET'
cmux send --workspace "$TARGET_SESSION" -- "$MESSAGE"
sleep 0.15
cmux send-key --workspace "$TARGET_SESSION" enter
SNIPPET
}
