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
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SWARM_FORGE_DIR="$WORKING_DIR/swarmforge"
SWARM_TOOLS_DIR="$WORKING_DIR/swarmtools"
WORKTREES_DIR="$WORKING_DIR/.worktrees"
CONFIG_FILE="$SWARM_FORGE_DIR/swarmforge.conf"
ROLES_DIR="$SWARM_FORGE_DIR"
CONSTITUTION_FILE="$SWARM_FORGE_DIR/constitution.prompt"
STATE_DIR="$WORKING_DIR/.swarmforge"
WINDOW_IDS_FILE="$STATE_DIR/window-ids"
SESSIONS_FILE="$STATE_DIR/sessions.tsv"
PROMPTS_DIR="$STATE_DIR/prompts"

typeset -a ROLES=()
typeset -a AGENTS=()
typeset -a SESSIONS=()
typeset -a DISPLAY_NAMES=()
typeset -a WORKTREE_NAMES=()
typeset -a WORKTREE_PATHS=()
typeset -A ROLE_INDEX=()
typeset -A WORKTREE_INDEX=()
typeset -i CLEANUP_OWNER_INDEX=1
typeset -i i=0

check_dependency() {
  if ! command -v "$1" &>/dev/null; then
    echo -e "${RED}Error:${RESET} '$1' is required but not installed."
    exit 1
  fi
}

ensure_initial_gitignore() {
  local gitignore_file="$WORKING_DIR/.gitignore"

  if [[ ! -f "$gitignore_file" ]]; then
    cat > "$gitignore_file" <<'EOF'
.swarmforge/
.worktrees/
swarmtools/
logs/
agent_context/
EOF
    return
  fi

  if ! grep -qx 'logs/' "$gitignore_file"; then
    echo 'logs/' >> "$gitignore_file"
  fi

  if ! grep -qx 'agent_context/' "$gitignore_file"; then
    echo 'agent_context/' >> "$gitignore_file"
  fi

  if ! grep -qx '.swarmforge/' "$gitignore_file"; then
    echo '.swarmforge/' >> "$gitignore_file"
  fi

  if ! grep -qx '.worktrees/' "$gitignore_file"; then
    echo '.worktrees/' >> "$gitignore_file"
  fi

  if ! grep -qx 'swarmtools/' "$gitignore_file"; then
    echo 'swarmtools/' >> "$gitignore_file"
  fi
}

initialize_git_repo() {
  if [[ -d "$WORKING_DIR/.git" ]]; then
    return
  fi

  git init "$WORKING_DIR" >/dev/null
  git -C "$WORKING_DIR" branch -M master >/dev/null
  ensure_initial_gitignore
  git -C "$WORKING_DIR" add .
  git -C "$WORKING_DIR" commit -m "Initial swarmforge repository" >/dev/null
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

worktree_path_for_name() {
  echo "$WORKTREES_DIR/$1"
}

parse_config() {
  if [[ ! -f "$CONFIG_FILE" ]]; then
    echo -e "${RED}Error:${RESET} Config not found at $CONFIG_FILE"
    exit 1
  fi

  if [[ ! -f "$CONSTITUTION_FILE" ]]; then
    echo -e "${RED}Error:${RESET} Constitution prompt not found at $CONSTITUTION_FILE"
    exit 1
  fi

  local line keyword role agent worktree line_no=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_no=$((line_no + 1))
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "${line[1]}" == "#" ]] && continue

    local -a fields
    fields=(${=line})
    if (( ${#fields[@]} != 4 )); then
      echo -e "${RED}Error:${RESET} Invalid config line $line_no: $line"
      exit 1
    fi

    keyword="${fields[1]}"
    role="${fields[2]}"
    agent="${fields[3]:l}"
    worktree="${fields[4]}"

    if [[ "$keyword" != "window" ]]; then
      echo -e "${RED}Error:${RESET} Unknown config directive on line $line_no: $keyword"
      exit 1
    fi

    if [[ -n "${ROLE_INDEX[$role]:-}" ]]; then
      echo -e "${RED}Error:${RESET} Duplicate role '$role' in $CONFIG_FILE"
      exit 1
    fi

    if [[ "$worktree" != "none" && "$worktree" != "master" && -n "${WORKTREE_INDEX[$worktree]:-}" ]]; then
      echo -e "${RED}Error:${RESET} Duplicate worktree '$worktree' in $CONFIG_FILE"
      exit 1
    fi

    if [[ "$worktree" == *"/"* || "$worktree" == "." || "$worktree" == ".." ]]; then
      echo -e "${RED}Error:${RESET} Invalid worktree '$worktree' for role '$role'"
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
    if [[ "$worktree" != "none" && "$worktree" != "master" ]]; then
      WORKTREE_INDEX[$worktree]=${#ROLES[@]}
    fi
    ROLES+=("$role")
    AGENTS+=("$agent")
    SESSIONS+=("$(session_name_for_role "$role")")
    DISPLAY_NAMES+=("$(display_name_for_role "$role")")
    WORKTREE_NAMES+=("$worktree")
    if [[ "$worktree" == "none" || "$worktree" == "master" ]]; then
      WORKTREE_PATHS+=("$WORKING_DIR")
    else
      WORKTREE_PATHS+=("$(worktree_path_for_name "$worktree")")
    fi
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

check_helper_scripts() {
  local helper
  for helper in swarm-cleanup.sh swarmlog.sh; do
    if [[ ! -x "$SCRIPT_DIR/$helper" ]]; then
      echo -e "${RED}Error:${RESET} Required helper script not found or not executable: $SCRIPT_DIR/$helper"
      exit 1
    fi
  done
}

write_notify_script() {
  cat > "$SWARM_TOOLS_DIR/notify-agent.sh" <<'EOF'
#!/usr/bin/env zsh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SESSIONS_FILE="$PROJECT_DIR/.swarmforge/sessions.tsv"
LOG_FILE="$PROJECT_DIR/logs/agent_messages.log"

if [[ $# -lt 2 ]]; then
  echo "Usage: notify-agent.sh <target-role-or-index> \"message\"" >&2
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
mkdir -p "$PROJECT_DIR/logs"
echo "[$TIMESTAMP] [$TARGET_SESSION] $MESSAGE" >> "$LOG_FILE"
tmux send-keys -t "${TARGET_SESSION}:0.0" -l -- "$MESSAGE"
sleep 0.15
tmux send-keys -t "${TARGET_SESSION}:0.0" C-m
sleep 0.05
tmux send-keys -t "${TARGET_SESSION}:0.0" C-j
EOF

  chmod +x "$SWARM_TOOLS_DIR/notify-agent.sh"
}

prepare_workspace() {
  mkdir -p "$WORKING_DIR/logs" "$WORKING_DIR/agent_context" "$WORKING_DIR/features" "$STATE_DIR" "$PROMPTS_DIR" "$SWARM_TOOLS_DIR" "$WORKTREES_DIR"
  check_helper_scripts
  write_sessions_file
  write_notify_script
}

prepare_worktrees() {
  local i worktree_name worktree_path branch_name
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    worktree_name="${WORKTREE_NAMES[$i]}"
    worktree_path="${WORKTREE_PATHS[$i]}"
    branch_name="swarmforge-${worktree_name}"

    if [[ "$worktree_name" == "none" || "$worktree_name" == "master" ]]; then
      continue
    fi

    if [[ -e "$worktree_path/.git" || -d "$worktree_path/.git" ]]; then
      continue
    fi

    git -C "$WORKING_DIR" worktree add --force -B "$branch_name" "$worktree_path" HEAD >/dev/null
  done
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
Read swarmforge/constitution.prompt, then read every file it refers to recursively, and obey all of those instructions.
Read swarmforge/${role}.prompt, then read every file it refers to recursively, and follow all of those instructions.
EOF
}

launch_role() {
  local index="$1"
  local role="${ROLES[$index]}"
  local agent="${AGENTS[$index]}"
  local session="${SESSIONS[$index]}"
  local display="${DISPLAY_NAMES[$index]}"
  local role_worktree="${WORKTREE_PATHS[$index]}"
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
      launch_cmd="export PATH='$SWARM_TOOLS_DIR:$SCRIPT_DIR':\$PATH && cd '$role_worktree' && claude --append-system-prompt-file '$prompt_file' --permission-mode acceptEdits -n 'SwarmForge ${display}' \"\$(cat '$prompt_file')\""
      ;;
    codex)
      launch_cmd="export PATH='$SWARM_TOOLS_DIR:$SCRIPT_DIR':\$PATH && cd '$role_worktree' && codex -C '$role_worktree' \"\$(cat '$prompt_file')\""
      ;;
  esac

  if [[ "$index" -eq "${CLEANUP_OWNER_INDEX}" ]]; then
    launch_cmd="${launch_cmd}; exit_code=\$?; nohup '$SCRIPT_DIR/swarm-cleanup.sh' '$WINDOW_IDS_FILE'"
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
check_dependency git
initialize_git_repo
parse_config
check_backend_dependencies
prepare_workspace
prepare_worktrees
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
echo -e "${GREEN}Tip: Use $WORKING_DIR/swarmtools/notify-agent.sh <role-or-index> \"message\" while the swarm is running.${RESET}"
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
