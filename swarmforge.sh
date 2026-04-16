#!/usr/bin/env zsh
set -euo pipefail

SESSION_PREFIX="swarmforge"
AGENT_WINDOW="swarm"
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

WORKING_DIR="${1:-$PWD}"
WORKING_DIR="$(cd "$WORKING_DIR" && pwd)"
CONFIG_FILE="$WORKING_DIR/swarmforge.conf"
ROLES_DIR="$WORKING_DIR/roles"
STATE_DIR="$WORKING_DIR/.swarmforge"
WINDOW_IDS_FILE="$STATE_DIR/window-ids"
SESSIONS_FILE="$STATE_DIR/sessions.tsv"
PROMPTS_DIR="$STATE_DIR/prompts"

typeset -a ROLES=()
typeset -a AGENTS=()
typeset -a SESSIONS=()
typeset -a DISPLAY_NAMES=()
typeset -A ROLE_INDEX=()
typeset -i CLEANUP_OWNER_INDEX=1
typeset -i i=0

check_dependency() {
  if ! command -v "$1" &>/dev/null; then
    echo -e "${RED}Error:${RESET} '$1' is required but not installed."
    exit 1
  fi
}

has_command() {
  command -v "$1" &>/dev/null
}

display_name_for_role() {
  local role="$1"
  local normalized="${role//[-_]/ }"
  local -a parts
  local part
  local label=""

  parts=(${=normalized})
  for part in "${parts[@]}"; do
    case "${part:l}" in
      e2e) part="E2E" ;;
      tdd) part="TDD" ;;
      qa) part="QA" ;;
      ui) part="UI" ;;
      api) part="API" ;;
      *) part="${(C)part}" ;;
    esac
    if [[ -n "$label" ]]; then
      label+=" "
    fi
    label+="$part"
  done

  echo "$label"
}

session_name_for_role() {
  echo "${SESSION_PREFIX}-$1"
}

parse_config() {
  if [[ ! -f "$CONFIG_FILE" ]]; then
    echo -e "${RED}Error:${RESET} Config not found at $CONFIG_FILE"
    exit 1
  fi

  local line keyword role agent line_no=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_no=$((line_no + 1))
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "${line[1]}" == "#" ]] && continue

    local -a fields
    fields=(${=line})
    if (( ${#fields[@]} != 3 )); then
      echo -e "${RED}Error:${RESET} Invalid config line $line_no: $line"
      exit 1
    fi

    keyword="${fields[1]}"
    role="${fields[2]}"
    agent="${fields[3]:l}"

    if [[ "$keyword" != "window" ]]; then
      echo -e "${RED}Error:${RESET} Unknown config directive on line $line_no: $keyword"
      exit 1
    fi

    if [[ -n "${ROLE_INDEX[$role]:-}" ]]; then
      echo -e "${RED}Error:${RESET} Duplicate role '$role' in $CONFIG_FILE"
      exit 1
    fi

    case "$agent" in
      claude|codex|none) ;;
      *)
        echo -e "${RED}Error:${RESET} Unsupported agent '$agent' for role '$role'"
        exit 1
        ;;
    esac

    if [[ "$agent" != "none" && ! -f "$ROLES_DIR/$role.prompt" ]]; then
      echo -e "${RED}Error:${RESET} Missing role prompt $ROLES_DIR/$role.prompt"
      exit 1
    fi

    ROLE_INDEX[$role]=${#ROLES[@]}
    ROLES+=("$role")
    AGENTS+=("$agent")
    SESSIONS+=("$(session_name_for_role "$role")")
    DISPLAY_NAMES+=("$(display_name_for_role "$role")")
  done < "$CONFIG_FILE"

  if (( ${#ROLES[@]} == 0 )); then
    echo -e "${RED}Error:${RESET} No windows defined in $CONFIG_FILE"
    exit 1
  fi
}

write_sessions_file() {
  : > "$SESSIONS_FILE"
  local i
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "$i" \
      "${ROLES[$i]}" \
      "${SESSIONS[$i]}" \
      "${DISPLAY_NAMES[$i]}" \
      "${AGENTS[$i]}" >> "$SESSIONS_FILE"
  done
}

write_swarm_log_script() {
  cat > "$WORKING_DIR/swarm-log.sh" <<'EOF'
#!/usr/bin/env zsh
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
echo "[$TIMESTAMP] [$1] $2" >> logs/agent_messages.log
echo "[$1] $2"
EOF
  chmod +x "$WORKING_DIR/swarm-log.sh"
}

write_notify_script() {
  cat > "$WORKING_DIR/notify-agent.sh" <<'EOF'
#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SESSIONS_FILE="$ROOT_DIR/.swarmforge/sessions.tsv"

if [[ $# -lt 2 ]]; then
  echo "Usage: ./notify-agent.sh <target-role-or-index> \"message\"" >&2
  exit 1
fi

if [[ ! -f "$SESSIONS_FILE" ]]; then
  echo "Sessions file not found: $SESSIONS_FILE" >&2
  exit 1
fi

resolve_session() {
  local target="${1:l}"
  local index role session display agent

  while IFS=$'\t' read -r index role session display agent; do
    if [[ "$target" == "${index:l}" || "$target" == "${role:l}" ]]; then
      echo "$session"
      return 0
    fi
  done < "$SESSIONS_FILE"

  return 1
}

TARGET_SESSION=$(resolve_session "$1") || {
  echo "Unknown target: $1" >&2
  exit 1
}

MESSAGE="${*:2}"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
echo "[$TIMESTAMP] [$TARGET_SESSION] $MESSAGE" >> "$ROOT_DIR/logs/agent_messages.log"
tmux set-buffer -- "$MESSAGE"
tmux paste-buffer -d -t "${TARGET_SESSION}:0.0"
tmux send-keys -t "${TARGET_SESSION}:0.0" Enter
EOF
  chmod +x "$WORKING_DIR/notify-agent.sh"
}

write_cleanup_script() {
  cat > "$WORKING_DIR/swarm-cleanup.sh" <<'EOF'
#!/usr/bin/env zsh
set -euo pipefail

WINDOW_IDS_FILE="$1"
shift

for session in "$@"; do
  tmux kill-session -t "$session" 2>/dev/null || true
done

sleep 1

if [[ -f "$WINDOW_IDS_FILE" ]]; then
  while IFS= read -r window_id; do
    [[ -n "$window_id" ]] || continue
    osascript \
      -e 'tell application "Terminal"' \
      -e 'try' \
      -e 'close (first window whose id is '"$window_id"') saving no' \
      -e 'end try' \
      -e 'end tell' >/dev/null 2>&1 || true
  done < "$WINDOW_IDS_FILE"
fi
EOF
  chmod +x "$WORKING_DIR/swarm-cleanup.sh"
}

prepare_workspace() {
  mkdir -p "$WORKING_DIR/logs" "$WORKING_DIR/agent_context" "$WORKING_DIR/features" "$STATE_DIR" "$PROMPTS_DIR"
  write_sessions_file
  write_swarm_log_script
  write_notify_script
  write_cleanup_script
}

check_backend_dependencies() {
  local i
  for (( i = 1; i <= ${#AGENTS[@]}; i++ )); do
    case "${AGENTS[$i]}" in
      claude) check_dependency claude ;;
      codex) check_dependency codex ;;
    esac
  done
}

create_role_session() {
  local session="$1"
  local title="$2"

  tmux new-session -d -s "$session" -n "$AGENT_WINDOW"
  tmux rename-window -t "$session:$AGENT_WINDOW" "$title"
  tmux set-window-option -t "$session:$title" allow-rename off
}

write_agent_instruction_file() {
  local role="$1"
  local prompt_file="$2"

  cat > "$prompt_file" <<EOF
Read roles/${role}.prompt and follow it.
EOF
}

launch_role() {
  local index="$1"
  local role="${ROLES[$index]}"
  local agent="${AGENTS[$index]}"
  local session="${SESSIONS[$index]}"
  local display="${DISPLAY_NAMES[$index]}"
  local prompt_file="$PROMPTS_DIR/${role}.md"
  local launch_cmd=""

  if [[ "$agent" == "none" ]]; then
    if [[ "$role" == "logger" ]]; then
      tmux send-keys -t "${session}:${display}.0" \
        "cd '$WORKING_DIR' && touch logs/agent_messages.log && tail -f logs/agent_messages.log" Enter
    fi
    echo -e "  ${CYAN}[${display}]${RESET} opened without agent backend"
    return
  fi

  write_agent_instruction_file "$role" "$prompt_file"

  case "$agent" in
    claude)
      launch_cmd="cd '$WORKING_DIR' && claude --append-system-prompt-file '$prompt_file' --permission-mode acceptEdits -n 'SwarmForge ${display}'"
      ;;
    codex)
      launch_cmd="cd '$WORKING_DIR' && codex -C '$WORKING_DIR' \"\$(cat '$prompt_file')\""
      ;;
  esac

  if [[ "$index" -eq "${CLEANUP_OWNER_INDEX}" ]]; then
    launch_cmd="${launch_cmd}; exit_code=\$?; nohup '$WORKING_DIR/swarm-cleanup.sh' '$WINDOW_IDS_FILE'"
    local session_name
    for session_name in "${SESSIONS[@]}"; do
      [[ -n "$session_name" ]] || continue
      launch_cmd+=" '$session_name'"
    done
    launch_cmd+=" >/dev/null 2>&1 &!; exit \$exit_code"
  fi

  tmux send-keys -t "${session}:${display}.0" "$launch_cmd" Enter
  echo -e "  ${CYAN}[${display}]${RESET} started in session ${session}"
}

open_terminal_window() {
  local session="$1"
  local title="$2"
  osascript <<EOF
tell application "Terminal"
  activate
  set newTab to do script ""
  do script "cd '$WORKING_DIR' && exec tmux attach-session -t '${session}'" in newTab
  set custom title of newTab to "${title}"
  return id of front window
end tell
EOF
}

choose_cleanup_owner() {
  if [[ -n "${ROLE_INDEX[architect]:-}" && "${AGENTS[$((ROLE_INDEX[architect] + 1))]}" != "none" ]]; then
    CLEANUP_OWNER_INDEX=$((ROLE_INDEX[architect] + 1))
    return
  fi

  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    if [[ "${AGENTS[$i]}" != "none" ]]; then
      CLEANUP_OWNER_INDEX=$i
      return
    fi
  done
}

check_dependency tmux
parse_config
check_backend_dependencies
prepare_workspace
choose_cleanup_owner

local_session=""
for local_session in "${SESSIONS[@]}"; do
  [[ -n "$local_session" ]] || continue
  if tmux has-session -t "$local_session" 2>/dev/null; then
    echo -e "${YELLOW}Existing SwarmForge session found: ${local_session}. Killing it...${RESET}"
    tmux kill-session -t "$local_session"
  fi
done

echo -e "${CYAN}${BOLD}"
echo "  ╔═══════════════════════════════════════════════╗"
echo "  ║           SwarmForge v1.0 Starting            ║"
echo "  ║   Disciplined agents build better software    ║"
echo "  ╚═══════════════════════════════════════════════╝"
echo -e "${RESET}"

echo -e "${GREEN}Launching SwarmForge tmux sessions...${RESET}"
for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
  create_role_session "${SESSIONS[$i]}" "${DISPLAY_NAMES[$i]}"
done

echo -e "${GREEN}Starting agents...${RESET}"
for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
  launch_role "$i"
done

echo ""
echo -e "${GREEN}${BOLD}SwarmForge is ready.${RESET}"
echo -e "Working directory: ${WORKING_DIR}"
echo -e "Sessions:"
for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
  echo -e "  ${DISPLAY_NAMES[$i]}: ${SESSIONS[$i]}"
done
echo ""
echo -e "${GREEN}Tip: Use ./notify-agent.sh <role-or-index> \"message\" from ${WORKING_DIR}.${RESET}"
echo -e "${GREEN}Tip: Reattach manually with 'tmux attach-session -t <session-name>' if needed.${RESET}"
echo ""

if has_command osascript; then
  echo -e "Opening separate Terminal windows for each session..."
  : > "$WINDOW_IDS_FILE"
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    open_terminal_window "${SESSIONS[$i]}" "SwarmForge ${DISPLAY_NAMES[$i]}" >> "$WINDOW_IDS_FILE"
  done
else
  echo -e "${YELLOW}osascript not found; attaching current shell to '${SESSIONS[$CLEANUP_OWNER_INDEX]}' instead.${RESET}"
  tmux attach-session -t "${SESSIONS[$CLEANUP_OWNER_INDEX]}"
fi
