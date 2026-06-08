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
ROLES_DIR="$SWARM_FORGE_DIR/roles"
CONSTITUTION_FILE="$SWARM_FORGE_DIR/constitution.prompt"
STATE_DIR="$WORKING_DIR/.swarmforge"
WINDOW_IDS_FILE="$STATE_DIR/window-ids"
WINDOW_STATE_FILE="$STATE_DIR/windows.tsv"
WINDOW_WATCHDOG_LOG="$STATE_DIR/window-watchdog.log"
SESSIONS_FILE="$STATE_DIR/sessions.tsv"
PROMPTS_DIR="$STATE_DIR/prompts"
TMUX_SOCKET_DIR="/private/tmp/swarmforge-${UID}"
PROJECT_SOCKET_ID="$(printf '%s' "$WORKING_DIR" | cksum)"
PROJECT_SOCKET_ID="${PROJECT_SOCKET_ID%% *}"
TMUX_SOCKET="$TMUX_SOCKET_DIR/$PROJECT_SOCKET_ID.sock"
TMUX_SOCKET_FILE="$STATE_DIR/tmux-socket"
TERMINAL_BACKEND=""

typeset -a ROLES=()
typeset -a AGENTS=()
typeset -a SESSIONS=()
typeset -a MUX_TARGETS=()
typeset -a DISPLAY_NAMES=()
typeset -a WORKTREE_NAMES=()
typeset -a WORKTREE_PATHS=()
typeset -A ROLE_INDEX=()
typeset -A WORKTREE_INDEX=()
typeset -i CLEANUP_OWNER_INDEX=1
typeset -i TMUX_WINDOW_BASE_INDEX=0
typeset -i TMUX_PANE_BASE_INDEX=0
typeset -i i=0

check_dependency() {
  if ! command -v "$1" &>/dev/null; then
    echo -e "${RED}Error:${RESET} '$1' is required but not installed."
    exit 1
  fi
}

get_tmux_option() {
  local option="$1"
  local scope="$2"
  local default_value="$3"
  local value=""

  case "$scope" in
    session)
      value="$(tmux -S "$TMUX_SOCKET" show-options -gqv "$option" 2>/dev/null || true)"
      ;;
    window)
      value="$(tmux -S "$TMUX_SOCKET" show-window-options -gqv "$option" 2>/dev/null || true)"
      ;;
  esac

  if [[ "$value" == <-> ]]; then
    echo "$value"
  else
    echo "$default_value"
  fi
}

detect_tmux_base_indexes() {
  local probe_session=""

  mkdir -p "$TMUX_SOCKET_DIR"
  if ! tmux -S "$TMUX_SOCKET" info >/dev/null 2>&1; then
    probe_session="swarmforge-probe-$$"
    tmux -S "$TMUX_SOCKET" new-session -d -s "$probe_session" "sleep 60" >/dev/null
  fi

  TMUX_WINDOW_BASE_INDEX="$(get_tmux_option base-index session 0)"
  TMUX_PANE_BASE_INDEX="$(get_tmux_option pane-base-index window 0)"

  if [[ -n "$probe_session" ]]; then
    tmux -S "$TMUX_SOCKET" kill-session -t "$probe_session" >/dev/null 2>&1 || true
  fi
}

tmux_agent_target() {
  local session="$1"
  local window="$2"

  echo "${session}:${window}.${TMUX_PANE_BASE_INDEX}"
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

ensure_runtime_git_excludes() {
  local exclude_file
  exclude_file="$(git -C "$WORKING_DIR" rev-parse --git-path info/exclude)"
  mkdir -p "${exclude_file:h}"
  touch "$exclude_file"

  local pattern
  for pattern in ".swarmforge/" ".worktrees/" "swarmtools/" "logs/" "agent_context/"; do
    if ! grep -qx "$pattern" "$exclude_file"; then
      echo "$pattern" >> "$exclude_file"
    fi
  done
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

source "$SCRIPT_DIR/swarm-terminal-adapter.sh"
source "$SCRIPT_DIR/swarm-mux.sh"

remove_nonessential_clone_files() {
  if [[ "${WORKING_DIR:t}" == "swarm-forge" ]]; then
    return
  fi

  if [[ -d "$STATE_DIR" ]]; then
    return
  fi

  rm -rf "$WORKING_DIR/examples"
}

display_name_for_role() {
  local role="$1"
  local normalized="${role//[-_]/ }"
  local -a parts
  local part
  local label=""

  parts=(${=normalized})
  for part in "${parts[@]}"; do
    part="${(C)part}"
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
      claude|codex|copilot|grok) ;;
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
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$i" \
      "${ROLES[$i]}" \
      "${MUX_TARGETS[$i]:-${SESSIONS[$i]}}" \
      "${DISPLAY_NAMES[$i]}" \
      "${AGENTS[$i]}" \
      "${WORKTREE_PATHS[$i]}" >> "$SESSIONS_FILE"
  done
}

check_helper_scripts() {
  local helper
  for helper in swarm-cleanup.sh swarm-window-watchdog.sh swarm-terminal-adapter.sh swarm-mux.sh swarmlog.sh; do
    if [[ ! -x "$SCRIPT_DIR/$helper" ]]; then
      echo -e "${RED}Error:${RESET} Required helper script not found or not executable: $SCRIPT_DIR/$helper"
      exit 1
    fi
  done

  for helper in terminal-app.sh ghostty.sh windows-terminal.sh none.sh; do
    if [[ ! -x "$SCRIPT_DIR/terminal-adapters/$helper" ]]; then
      echo -e "${RED}Error:${RESET} Required terminal adapter not found or not executable: $SCRIPT_DIR/terminal-adapters/$helper"
      exit 1
    fi
  done
}

write_deliver_script() {
  cat > "$SWARM_TOOLS_DIR/swarmforge-deliver.sh" <<'DELIVEREOF'
#!/usr/bin/env zsh
set -euo pipefail

# Args: project_dir mux_backend mux_target display_name worktree_path logbook_path bundle_path hash message_file
PROJECT_DIR="$1"
MUX_BACKEND="$2"
MUX_TARGET="$3"
DISPLAY_NAME="$4"
WORKTREE_PATH="$5"
LOGBOOK_PATH="$6"
BUNDLE_PATH="$7"
HASH="$8"
MESSAGE_FILE="$9"

# 1. Append executing entry to receiver logbook
LOGBOOK_PATH="$LOGBOOK_PATH" python3 -c '
import json, os
from datetime import datetime, timezone
p = os.environ["LOGBOOK_PATH"]
log = []
try:
  with open(p) as f: log = json.load(f)
except: pass
log.append({"status": "executing", "timestamp": datetime.now(timezone.utc).isoformat()})
os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
with open(p, "w") as f: json.dump(log, f, indent=2)
'

# 2. git reset --hard to handoff commit
git -C "$WORKTREE_PATH" reset --hard "$HASH"

send_to_agent() {
  local text="$1"
  if [[ "$MUX_BACKEND" == "cmux" ]]; then
    cmux send --workspace "$MUX_TARGET" -- "$text"
    sleep 0.15
    cmux send-key --workspace "$MUX_TARGET" enter
  else
    local socket_file="$PROJECT_DIR/.swarmforge/tmux-socket"
    [[ -f "$socket_file" ]] || return 1
    local socket win_idx pane_idx
    socket="$(< "$socket_file")"
    win_idx="$(tmux -S "$socket" show-options -gqv base-index 2>/dev/null || echo 0)"
    pane_idx="$(tmux -S "$socket" show-window-options -gqv pane-base-index 2>/dev/null || echo 0)"
    [[ "$win_idx" == <-> ]] || win_idx=0
    [[ "$pane_idx" == <-> ]] || pane_idx=0
    tmux -S "$socket" send-keys -t "${MUX_TARGET}:${win_idx}.${pane_idx}" -l -- "$text"
    sleep 0.15
    tmux -S "$socket" send-keys -t "${MUX_TARGET}:${win_idx}.${pane_idx}" C-m
    sleep 0.05
    tmux -S "$socket" send-keys -t "${MUX_TARGET}:${win_idx}.${pane_idx}" C-j
  fi
}

# 3. /clear
send_to_agent "/clear"
# 4. Sleep 1s
sleep 1
# 5. /rename
send_to_agent "/rename SwarmForge ${DISPLAY_NAME}"
# 6. Send bundle cache
[[ -f "$BUNDLE_PATH" ]] && send_to_agent "$(< "$BUNDLE_PATH")"
# 7. Send message
send_to_agent "$(< "$MESSAGE_FILE")"
DELIVEREOF
  chmod +x "$SWARM_TOOLS_DIR/swarmforge-deliver.sh"
}

write_notify_script() {
  local mux_backend
  if mux_is_cmux; then
    mux_backend="cmux"
  else
    mux_backend="tmux"
  fi

  {
    echo '#!/usr/bin/env zsh'
    echo 'set -euo pipefail'
    printf 'MUX_BACKEND=%q\n' "$mux_backend"
    printf 'DELIVER_SCRIPT=%q\n' "$SWARM_TOOLS_DIR/swarmforge-deliver.sh"
    printf 'PROMPTS_DIR=%q\n' "$PROMPTS_DIR"
  } > "$SWARM_TOOLS_DIR/notify-agent.sh"

  cat >> "$SWARM_TOOLS_DIR/notify-agent.sh" <<'EOF'

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SENDER_WORKTREE="${SWARMFORGE_SENDER_WORKTREE:-$PWD}"

find_project_dir() {
  local git_common_dir
  if git_common_dir=$(git -C "$SENDER_WORKTREE" rev-parse --git-common-dir 2>/dev/null); then
    [[ "$git_common_dir" != /* ]] && git_common_dir="$(cd "$SENDER_WORKTREE/$git_common_dir" && pwd)"
    local project_dir="${git_common_dir:h}"
    [[ -f "$project_dir/.swarmforge/sessions.tsv" ]] && echo "$project_dir" && return 0
  fi
  echo "${SCRIPT_DIR:h}"
}

PROJECT_DIR="$(find_project_dir)"
SESSIONS_FILE="$PROJECT_DIR/.swarmforge/sessions.tsv"

if [[ $# -lt 2 ]]; then
  echo "Usage: notify-agent.sh <target-role-or-index> \"message\"" >&2
  echo "       notify-agent.sh <target-role-or-index> --file <message-file>" >&2
  exit 1
fi

[[ -f "$SESSIONS_FILE" ]] || { echo "Sessions file not found: $SESSIONS_FILE" >&2; exit 1; }

TARGET="${1:l}"
shift

msg_file=$(mktemp)
trap 'rm -f "$msg_file"' EXIT

if [[ "${1:-}" == "--file" ]]; then
  [[ $# -ne 2 ]] && { echo "Usage: notify-agent.sh <target-role-or-index> --file <message-file>" >&2; exit 1; }
  [[ -f "$2" ]] || { echo "Message file not found: $2" >&2; exit 1; }
  cp "$2" "$msg_file"
else
  printf '%s' "$*" > "$msg_file"
fi

# Validate sender worktree is clean
if ! git -C "$SENDER_WORKTREE" diff --quiet 2>/dev/null || ! git -C "$SENDER_WORKTREE" diff --cached --quiet 2>/dev/null; then
  echo "Error: sender worktree is dirty — commit or stash before notifying." >&2
  exit 1
fi

# Read sender HEAD and append [handoff] to message
SENDER_HASH=$(git -C "$SENDER_WORKTREE" rev-parse HEAD 2>/dev/null || echo "unknown")
printf '\n[handoff] merge-commit=%s' "$SENDER_HASH" >> "$msg_file"

SENDER_LOGBOOK="$SENDER_WORKTREE/logbook.json"
FULL_MESSAGE="$(< "$msg_file")"

# Append sent + executed to sender logbook
LOGBOOK="$SENDER_LOGBOOK" TARGET_ROLE="$TARGET" MSG="$FULL_MESSAGE" HASH="$SENDER_HASH" python3 -c '
import json, os
from datetime import datetime, timezone
p = os.environ["LOGBOOK"]
log = []
try:
  with open(p) as f: log = json.load(f)
except: pass
now = datetime.now(timezone.utc).isoformat()
log.append({"status": "sent", "target": os.environ["TARGET_ROLE"], "message": os.environ["MSG"], "hash": os.environ["HASH"], "timestamp": now})
log.append({"status": "executed", "timestamp": now})
os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
with open(p, "w") as f: json.dump(log, f, indent=2)
'

# Resolve receiver from sessions.tsv (cols: index role mux_target display agent worktree_path)
receiver_role="" receiver_mux="" receiver_display="" receiver_worktree=""
while IFS=$'\t' read -r idx role mux_target display agent worktree_path; do
  if [[ "$TARGET" == "${idx:l}" || "$TARGET" == "${role:l}" ]]; then
    receiver_role="$role"
    receiver_mux="$mux_target"
    receiver_display="$display"
    receiver_worktree="$worktree_path"
    break
  fi
done < "$SESSIONS_FILE"

[[ -n "$receiver_role" ]] || { echo "Unknown target: $TARGET" >&2; exit 1; }

RECEIVER_LOGBOOK="$receiver_worktree/logbook.json"
RECEIVER_BUNDLE="$PROMPTS_DIR/${receiver_role}.md"

# Check receiver logbook for last terminal state
receiver_state=$(LOGBOOK="$RECEIVER_LOGBOOK" python3 -c '
import json, os, sys
log = []
try:
  with open(os.environ["LOGBOOK"]) as f: log = json.load(f)
except: pass
state = "none"
for e in reversed(log):
  s = e.get("status", "")
  if s in ("executing", "executed"):
    state = s
    break
sys.stdout.write(state)
' 2>/dev/null || echo "none")

if [[ "$receiver_state" != "executing" ]]; then
  exec "$DELIVER_SCRIPT" "$PROJECT_DIR" "$MUX_BACKEND" "$receiver_mux" "$receiver_display" "$receiver_worktree" "$RECEIVER_LOGBOOK" "$RECEIVER_BUNDLE" "$SENDER_HASH" "$msg_file"
else
  LOGBOOK="$RECEIVER_LOGBOOK" MSG="$FULL_MESSAGE" HASH="$SENDER_HASH" python3 -c '
import json, os
from datetime import datetime, timezone
p = os.environ["LOGBOOK"]
log = []
try:
  with open(p) as f: log = json.load(f)
except: pass
log.append({"status": "pending", "message": os.environ["MSG"], "hash": os.environ["HASH"], "timestamp": datetime.now(timezone.utc).isoformat()})
os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
with open(p, "w") as f: json.dump(log, f, indent=2)
  '
  echo "Queued: receiver is currently executing. Delivery will occur at next idle."
fi
EOF

  chmod +x "$SWARM_TOOLS_DIR/notify-agent.sh"
}

write_stop_hook() {
  local index="$1"
  local role="${ROLES[$index]}"
  local worktree_path="${WORKTREE_PATHS[$index]}"
  local display_name="${DISPLAY_NAMES[$index]}"
  local mux_target="${MUX_TARGETS[$index]:-${SESSIONS[$index]}}"
  local logbook_path="$worktree_path/logbook.json"
  local bundle_path="$PROMPTS_DIR/${role}.md"
  local stop_hook_script="$SWARM_TOOLS_DIR/stop-hook-${role}.sh"
  local mux_backend
  if mux_is_cmux; then
    mux_backend="cmux"
  else
    mux_backend="tmux"
  fi

  {
    echo '#!/usr/bin/env zsh'
    echo 'set -euo pipefail'
    printf 'LOGBOOK_PATH=%q\n' "$logbook_path"
    printf 'BUNDLE_PATH=%q\n' "$bundle_path"
    printf 'DISPLAY_NAME=%q\n' "$display_name"
    printf 'MUX_BACKEND=%q\n' "$mux_backend"
    printf 'MUX_TARGET=%q\n' "$mux_target"
    printf 'WORKTREE_PATH=%q\n' "$worktree_path"
    printf 'DELIVER_SCRIPT=%q\n' "$SWARM_TOOLS_DIR/swarmforge-deliver.sh"
    printf 'PROJECT_DIR=%q\n' "$WORKING_DIR"
  } > "$stop_hook_script"

  cat >> "$stop_hook_script" <<'HOOKEOF'

pending_msg_file=$(mktemp)
trap 'rm -f "$pending_msg_file"' EXIT

# Query logbook: find last terminal state and first pending after last executing
py_out=$(LOGBOOK_PATH="$LOGBOOK_PATH" PENDING_OUT="$pending_msg_file" python3 -c '
import json, os, sys
p = os.environ["LOGBOOK_PATH"]
out = os.environ["PENDING_OUT"]
log = []
try:
  with open(p) as f: log = json.load(f)
except: pass
last_term = "none"
last_exec_idx = -1
for i in range(len(log)-1, -1, -1):
  s = log[i].get("status", "")
  if s in ("executing", "executed") and last_term == "none": last_term = s
  if s == "executing" and last_exec_idx < 0: last_exec_idx = i
  if last_term != "none" and last_exec_idx >= 0: break
pending_hash = ""
if last_term == "executed" and last_exec_idx >= 0:
  for i in range(last_exec_idx+1, len(log)):
    if log[i].get("status") == "pending":
      with open(out, "w") as f: f.write(log[i].get("message", ""))
      pending_hash = log[i].get("hash", "")
      break
sys.stdout.write(last_term + "\t" + pending_hash)
' 2>/dev/null || printf 'none\t')

last_term="${py_out%%	*}"
pending_hash="${py_out##*	}"

[[ "$last_term" != "executed" ]] && exit 0
[[ -s "$pending_msg_file" ]] || exit 0

exec "$DELIVER_SCRIPT" "$PROJECT_DIR" "$MUX_BACKEND" "$MUX_TARGET" "$DISPLAY_NAME" "$WORKTREE_PATH" "$LOGBOOK_PATH" "$BUNDLE_PATH" "$pending_hash" "$pending_msg_file"
HOOKEOF

  chmod +x "$stop_hook_script"

  local settings_dir="$worktree_path/.claude"
  local settings_file="$settings_dir/settings.local.json"
  mkdir -p "$settings_dir"
  SETTINGS_FILE="$settings_file" HOOK_CMD="$stop_hook_script" python3 -c '
import json, os
p = os.environ["SETTINGS_FILE"]
hook_cmd = os.environ["HOOK_CMD"]
cfg = {}
try:
  with open(p) as f: cfg = json.load(f)
except: pass
cfg.setdefault("hooks", {}).setdefault("Stop", [])
exists = any(
  any(c.get("command") == hook_cmd for c in h.get("hooks", []))
  for h in cfg["hooks"]["Stop"]
)
if not exists:
  cfg["hooks"]["Stop"].append({"hooks": [{"type": "command", "command": hook_cmd}]})
with open(p, "w") as f: json.dump(cfg, f, indent=2)
  '
}

prepare_workspace() {
  mkdir -p "$WORKING_DIR/logs" "$WORKING_DIR/agent_context" "$STATE_DIR" "$PROMPTS_DIR" "$SWARM_TOOLS_DIR" "$WORKTREES_DIR" "$TMUX_SOCKET_DIR"
  printf '%s\n' "$TMUX_SOCKET" > "$TMUX_SOCKET_FILE"
  check_helper_scripts
  write_sessions_file
  write_deliver_script
  write_notify_script
}

write_worktree_permissions() {
  local worktree_path="$1"
  local settings_dir="$worktree_path/.claude"
  local settings_file="$settings_dir/settings.local.json"

  mkdir -p "$settings_dir"
  SETTINGS_FILE="$settings_file" python3 -c '
import json, os
p = os.environ["SETTINGS_FILE"]
cfg = {}
try:
  with open(p) as f: cfg = json.load(f)
except: pass
cfg["autoCompactEnabled"] = True
cfg.setdefault("env", {})
cfg["env"]["CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"] = "88"
cfg["env"]["CLAUDE_CODE_AUTO_COMPACT_WINDOW"] = "200000"
with open(p, "w") as f: json.dump(cfg, f, indent=2)
  '
}

write_worktree_notify_wrapper() {
  local worktree_path="$1"
  local wrapper_dir="$worktree_path/swarmtools"
  local wrapper="$wrapper_dir/notify-agent.sh"
  local canonical_notify="$SWARM_TOOLS_DIR/notify-agent.sh"

  mkdir -p "$wrapper_dir"
  {
    echo '#!/usr/bin/env zsh'
    echo 'set -euo pipefail'
    printf 'CANONICAL_NOTIFY_AGENT=%q\n' "$canonical_notify"
    printf 'export SWARMFORGE_SENDER_WORKTREE=%q\n' "$(cd "$worktree_path" && pwd)"
    echo 'exec "$CANONICAL_NOTIFY_AGENT" "$@"'
  } > "$wrapper"
  chmod +x "$wrapper"
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

    if [[ ! -e "$worktree_path/.git" && ! -d "$worktree_path/.git" ]]; then
      git -C "$WORKING_DIR" worktree add --force -B "$branch_name" "$worktree_path" HEAD >/dev/null
    fi

    write_worktree_notify_wrapper "$worktree_path"
    write_worktree_permissions "$worktree_path"
  done
}

check_backend_dependencies() {
  local i
  for (( i = 1; i <= ${#AGENTS[@]}; i++ )); do
    case "${AGENTS[$i]}" in
      claude) check_dependency claude ;;
      codex) check_dependency codex ;;
      copilot) check_dependency copilot ;;
      grok) check_dependency grok ;;
    esac
  done
}

create_role_session() {
  local session="$1"
  local title="$2"

  tmux -S "$TMUX_SOCKET" new-session -d -s "$session" -n "$AGENT_WINDOW"
  tmux -S "$TMUX_SOCKET" rename-window -t "$session:$AGENT_WINDOW" "$title"
  tmux -S "$TMUX_SOCKET" set-window-option -t "$session:$title" allow-rename off
}

resolve_prompt_bundle() {
  local role="$1"
  typeset -a bundle=()
  typeset -A seen=()
  typeset -a queue=("$CONSTITUTION_FILE" "$ROLES_DIR/${role}.prompt")
  local file rel_path ref ref_abs

  while (( ${#queue[@]} > 0 )); do
    file="${queue[1]}"
    shift queue

    rel_path="${file#${WORKING_DIR}/}"
    [[ ${+seen[$rel_path]} -eq 1 ]] && continue
    [[ ! -f "$file" ]] && continue

    seen[$rel_path]=1
    bundle+=("$rel_path")

    while IFS= read -r ref; do
      [[ -z "$ref" ]] && continue
      ref_abs="$WORKING_DIR/$ref"
      [[ ${+seen[$ref]} -eq 0 ]] && queue+=("$ref_abs")
    done < <(grep -oE 'swarmforge/[A-Za-z0-9_./-]+\.prompt' "$file" 2>/dev/null || true)
  done

  printf '%s\n' "${bundle[@]}"
}

write_agent_instruction_file() {
  local role="$1"
  local prompt_file="$2"
  typeset -a bundle_files=()
  local rel abs_path

  while IFS= read -r rel; do
    [[ -n "$rel" ]] && bundle_files+=("$rel")
  done < <(resolve_prompt_bundle "$role")

  {
    printf '<swarmforge_agent_context role="%s">\n' "$role"
    printf '<instructions>\n'
    printf 'This prompt bundle is pre-resolved. Do not open or re-read any swarmforge/*.prompt files — all relevant instructions are already included below.\n'
    printf '</instructions>\n'
    for rel in "${bundle_files[@]}"; do
      abs_path="$WORKING_DIR/$rel"
      [[ -f "$abs_path" ]] || continue
      printf '<file path="%s">\n' "$rel"
      cat "$abs_path"
      printf '\n</file>\n'
    done
    printf '</swarmforge_agent_context>\n'
  } > "$prompt_file"
}

send_initial_grok_prompt() {
  local session="$1"
  local display="$2"
  local prompt_file="$3"

  (
    sleep 3
    tmux -S "$TMUX_SOCKET" send-keys -t "$(tmux_agent_target "$session" "$display")" -l -- "$(< "$prompt_file")"
    sleep 0.15
    tmux -S "$TMUX_SOCKET" send-keys -t "$(tmux_agent_target "$session" "$display")" C-m
    sleep 0.05
    tmux -S "$TMUX_SOCKET" send-keys -t "$(tmux_agent_target "$session" "$display")" C-j
  ) &!
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

  write_agent_instruction_file "$role" "$prompt_file"
  write_stop_hook "$index"

  case "$agent" in
    claude)
      launch_cmd="export PATH='$SWARM_TOOLS_DIR:$SCRIPT_DIR':\$PATH && cd '$role_worktree' && claude --append-system-prompt-file '$prompt_file' --permission-mode acceptEdits -n 'SwarmForge ${display}' \"\$(cat '$prompt_file')\""
      ;;
    codex)
      launch_cmd="export PATH='$SWARM_TOOLS_DIR:$SCRIPT_DIR':\$PATH && cd '$role_worktree' && codex -C '$role_worktree' \"\$(cat '$prompt_file')\""
      ;;
    copilot)
      launch_cmd="export PATH='$SWARM_TOOLS_DIR:$SCRIPT_DIR':\$PATH && cd '$role_worktree' && copilot -C '$role_worktree' --name 'SwarmForge ${display}' -i \"\$(cat '$prompt_file')\""
      ;;
    grok)
      launch_cmd="export PATH='$SWARM_TOOLS_DIR:$SCRIPT_DIR':\$PATH && cd '$role_worktree' && grok --cwd '$role_worktree' --permission-mode acceptEdits --rules \"\$(cat '$prompt_file')\""
      ;;
  esac

  if [[ "$index" -eq "${CLEANUP_OWNER_INDEX}" ]]; then
    if mux_is_cmux; then
      launch_cmd="${launch_cmd}; exit_code=\$?; nohup '$SCRIPT_DIR/swarm-cleanup.sh' $(mux_cleanup_args) >/dev/null 2>&1 &!; exit \$exit_code"
    else
      launch_cmd="${launch_cmd}; exit_code=\$?; SWARMFORGE_TERMINAL_BACKEND='$TERMINAL_BACKEND' nohup '$SCRIPT_DIR/swarm-cleanup.sh' '$TMUX_SOCKET' '$WINDOW_IDS_FILE'"
      local session_name
      for session_name in "${SESSIONS[@]}"; do
        [[ -n "$session_name" ]] || continue
        launch_cmd+=" '$session_name'"
      done
      launch_cmd+=" >/dev/null 2>&1 &!; exit \$exit_code"
    fi
  fi

  if mux_is_cmux; then
    mux_deliver "$index" "$launch_cmd"
    echo -e "  ${CYAN}[${display}]${RESET} started in workspace ${MUX_TARGETS[$index]}"
  else
    tmux -S "$TMUX_SOCKET" send-keys -t "$(tmux_agent_target "$session" "$display")" "$launch_cmd" Enter
    if [[ "$agent" == "grok" ]]; then
      send_initial_grok_prompt "$session" "$display" "$prompt_file"
    fi
    echo -e "  ${CYAN}[${display}]${RESET} started in session ${session}"
  fi
}

choose_cleanup_owner() {
  CLEANUP_OWNER_INDEX=1
}

check_dependency "$(mux_dependency)"
check_dependency git
check_dependency python3
if ! mux_is_cmux; then
  detect_tmux_base_indexes
fi
remove_nonessential_clone_files
initialize_git_repo
ensure_runtime_git_excludes
parse_config
mux_init_targets
check_backend_dependencies
prepare_workspace
prepare_worktrees
choose_cleanup_owner
# Record the active multiplexer so `swarm stop` can tear down the right way
# without the caller re-specifying SWARM_MUX.
printf '%s\n' "$SWARM_MUX" > "$STATE_DIR/mux-backend"
if ! mux_is_cmux; then
  TERMINAL_BACKEND="$(detect_terminal_backend)"
  load_terminal_backend "$TERMINAL_BACKEND"
  # Record the active backend so `swarm stop` can close windows with the same
  # adapter without the caller re-specifying SWARMFORGE_TERMINAL.
  printf '%s\n' "$TERMINAL_BACKEND" > "$STATE_DIR/terminal-backend"
fi

if mux_is_cmux; then
  mux_kill_existing
else
  local_session=""
  for local_session in "${SESSIONS[@]}"; do
    [[ -n "$local_session" ]] || continue
    if tmux -S "$TMUX_SOCKET" has-session -t "$local_session" 2>/dev/null; then
      echo -e "${YELLOW}Existing SwarmForge session found: ${local_session}. Killing it...${RESET}"
      tmux -S "$TMUX_SOCKET" kill-session -t "$local_session"
    fi
  done
fi

echo -e "${CYAN}${BOLD}"
echo "  ╔═══════════════════════════════════════════════╗"
echo "  ║           SwarmForge v1.0 Starting            ║"
echo "  ║   Disciplined agents build better software    ║"
echo "  ╚═══════════════════════════════════════════════╝"
echo -e "${RESET}"

if mux_is_cmux; then
  echo -e "${GREEN}Creating a grouped cmux workspace for each role...${RESET}"
  mux_create_all
else
  echo -e "${GREEN}Launching SwarmForge tmux sessions...${RESET}"
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    create_role_session "${SESSIONS[$i]}" "${DISPLAY_NAMES[$i]}"
  done
fi

echo -e "${GREEN}Starting agents...${RESET}"
for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
  launch_role "$i"
done

echo ""
echo -e "${GREEN}${BOLD}SwarmForge is ready.${RESET}"
echo -e "Working directory: ${WORKING_DIR}"
echo -e "Sessions:"
for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
  echo -e "  ${DISPLAY_NAMES[$i]}: ${MUX_TARGETS[$i]:-${SESSIONS[$i]}}"
done
echo ""
echo -e "${GREEN}Tip: Use $WORKING_DIR/swarmtools/notify-agent.sh <role-or-index> --file <message-file> while the swarm is running.${RESET}"
if ! mux_is_cmux; then
  echo -e "${GREEN}Tip: Reattach manually with 'tmux -S $TMUX_SOCKET attach-session -t <session-name>' if needed.${RESET}"
fi
echo ""

if mux_is_cmux; then
  mux_open_views
  echo -e "${GREEN}Agents are running in a grouped set of cmux workspaces (SwarmForge · ${WORKING_DIR:t}).${RESET}"
elif terminal_backend_can_open_sessions; then
  echo -e "Opening separate $(terminal_backend_label) surfaces for each session..."
  if terminal_backend_tracks_windows; then
    : > "$WINDOW_IDS_FILE"
    : > "$WINDOW_STATE_FILE"
  fi
  previous_window_id=""
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    window_id="$(terminal_open_session "${SESSIONS[$i]}" "SwarmForge ${DISPLAY_NAMES[$i]}" "$previous_window_id")"
    if terminal_backend_tracks_windows; then
      echo "$window_id" >> "$WINDOW_IDS_FILE"
      printf '%s\t%s\t%s\t%s\n' \
        "$i" \
        "$window_id" \
        "${SESSIONS[$i]}" \
        "SwarmForge ${DISPLAY_NAMES[$i]}" >> "$WINDOW_STATE_FILE"
      previous_window_id="$window_id"
    fi
  done
  if terminal_backend_tracks_windows; then
    nohup "$SCRIPT_DIR/swarm-window-watchdog.sh" \
      "$WINDOW_STATE_FILE" \
      "$WINDOW_IDS_FILE" \
      "$CLEANUP_OWNER_INDEX" \
      "$TMUX_SOCKET" \
      "$WORKING_DIR" \
      "$TERMINAL_BACKEND" > "$WINDOW_WATCHDOG_LOG" 2>&1 &
  else
    echo -e "${YELLOW}$(terminal_backend_label) surfaces are not trackable; window watchdog is disabled for this backend.${RESET}"
  fi
else
  echo -e "${YELLOW}No terminal backend found; attaching current shell to '${SESSIONS[$CLEANUP_OWNER_INDEX]}' instead.${RESET}"
  tmux -S "$TMUX_SOCKET" attach-session -t "${SESSIONS[$CLEANUP_OWNER_INDEX]}"
fi
