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
WORKTREES_DIR="$WORKING_DIR/.worktrees"
CONFIG_FILE="$SWARM_FORGE_DIR/swarmforge.conf"
ROLES_DIR="$SWARM_FORGE_DIR/roles"
CONSTITUTION_FILE="$SWARM_FORGE_DIR/constitution.prompt"
STATE_DIR="$WORKING_DIR/.swarmforge"
NOTIFY_DIR="$STATE_DIR/notify"
WINDOW_IDS_FILE="$STATE_DIR/window-ids"
WINDOW_STATE_FILE="$STATE_DIR/windows.tsv"
WINDOW_WATCHDOG_LOG="$STATE_DIR/window-watchdog.log"
SESSIONS_FILE="$STATE_DIR/sessions.tsv"
PROMPTS_DIR="$STATE_DIR/prompts"
QA_HOLDOUT_PATH="${SWARMFORGE_QA_HOLDOUT_PATH:-qa-e2e}"
TMUX_SOCKET_DIR="/private/tmp/swarmforge-${UID}"
PROJECT_SOCKET_ID="$(printf '%s' "$WORKING_DIR" | cksum)"
PROJECT_SOCKET_ID="${PROJECT_SOCKET_ID%% *}"
TMUX_SOCKET="$TMUX_SOCKET_DIR/$PROJECT_SOCKET_ID.sock"
TMUX_SOCKET_FILE="$STATE_DIR/tmux-socket"
TMUX_ENV_FILE="$STATE_DIR/tmux-env"
TERMINAL_BACKEND=""

typeset -a ROLES=()
typeset -a AGENTS=()
typeset -a SESSIONS=()
typeset -a DISPLAY_NAMES=()
typeset -a WORKTREE_NAMES=()
typeset -a WORKTREE_PATHS=()
typeset -a ROLE_MODELS=()
typeset -a ROLE_EFFORTS=()
typeset -a ROLE_ADVISORS=()
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
EOF
    return
  fi

  if ! grep -qx '.swarmforge/' "$gitignore_file"; then
    echo '.swarmforge/' >> "$gitignore_file"
  fi

  if ! grep -qx '.worktrees/' "$gitignore_file"; then
    echo '.worktrees/' >> "$gitignore_file"
  fi

}

ensure_runtime_git_excludes() {
  local exclude_file
  exclude_file="$(git -C "$WORKING_DIR" rev-parse --git-path info/exclude)"
  mkdir -p "${exclude_file:h}"
  touch "$exclude_file"

  local pattern
  for pattern in ".swarmforge/" ".worktrees/"; do
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
    if (( ${#fields[@]} < 4 )); then
      echo -e "${RED}Error:${RESET} Invalid config line $line_no: $line"
      exit 1
    fi

    keyword="${fields[1]}"
    role="${fields[2]}"
    agent="${fields[3]:l}"
    worktree="${fields[4]}"

    local role_model="" role_effort="" role_advisor="" kv="" key="" val="" kv_i=0
    for (( kv_i = 5; kv_i <= ${#fields[@]}; kv_i++ )); do
      kv="${fields[$kv_i]}"
      key="${kv%%=*}"
      val="${kv#*=}"
      case "$key" in
        model)   role_model="$val" ;;
        effort)  role_effort="$val" ;;
        advisor) role_advisor="$val" ;;
      esac
    done

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
    ROLE_MODELS+=("$role_model")
    ROLE_EFFORTS+=("$role_effort")
    ROLE_ADVISORS+=("$role_advisor")
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
  for helper in swarm-handoff notify-agent.sh send-handoff.sh receive-handoff.sh resend-handoff.sh complete-handoff.sh handoff-lib.sh swarm-cleanup.sh swarm-stop.sh swarm-window-watchdog.sh swarm-terminal-adapter.sh; do
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

install_shared_constitution_articles() {
  local target_root="$1"
  local target_dir="$target_root/swarmforge/constitution/articles"
  local source_dir article target
  local -a source_dirs

  source_dirs=("$SCRIPT_DIR/shared-articles" "${SCRIPT_DIR:h}/constitution/articles")
  mkdir -p "$target_dir"

  for source_dir in "${source_dirs[@]}"; do
    [[ -d "$source_dir" ]] || continue
    for article in "$source_dir"/*(.N); do
      [[ -f "$article" ]] || continue
      target="$target_dir/${article:t}"
      [[ "$article" == "$target" ]] && continue
      [[ -e "$target" ]] && continue
      cp "$article" "$target"
    done
  done
}

prepare_workspace() {
  mkdir -p "$STATE_DIR" "$NOTIFY_DIR" "$PROMPTS_DIR" "$WORKTREES_DIR" "$TMUX_SOCKET_DIR"
  printf '%s\n' "$TMUX_SOCKET" > "$TMUX_SOCKET_FILE"
  check_helper_scripts
  write_sessions_file
}

write_tmux_env_file() {
  local tmux_value
  tmux_value="$(tmux -S "$TMUX_SOCKET" display-message -p '#{socket_path},#{pid},#{pane_id}')"
  printf '%s\n' "$tmux_value" > "$TMUX_ENV_FILE"
}

prepare_worktrees() {
  local i role worktree_name worktree_path branch_name
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    role="${ROLES[$i]}"
    worktree_name="${WORKTREE_NAMES[$i]}"
    worktree_path="${WORKTREE_PATHS[$i]}"
    branch_name="swarmforge-${worktree_name}"

    if [[ "$worktree_name" == "none" || "$worktree_name" == "master" ]]; then
      continue
    fi

    if [[ ! -e "$worktree_path/.git" && ! -d "$worktree_path/.git" ]]; then
      git -C "$WORKING_DIR" worktree add --force -B "$branch_name" "$worktree_path" HEAD >/dev/null
    fi
    write_worktree_settings "$worktree_path"

    if [[ "$role" != "specifier" && "$role" != "QA" ]]; then
      git -C "$worktree_path" sparse-checkout init --no-cone >/dev/null 2>&1
      local worktree_git_dir
      worktree_git_dir="$(git -C "$worktree_path" rev-parse --git-dir 2>/dev/null)"
      {
        printf '/*\n'
        printf '!/%s/\n' "$QA_HOLDOUT_PATH"
      } > "${worktree_git_dir}/info/sparse-checkout" 2>/dev/null \
        || git -C "$worktree_path" sparse-checkout set --no-cone '/*' "!/${QA_HOLDOUT_PATH}/" >/dev/null 2>&1
      git -C "$worktree_path" read-tree -mu HEAD >/dev/null 2>&1 || true
    fi
  done
}

sync_worktree_scripts() {
  local i worktree_path role_scripts_dir role_state_dir
  for (( i = 1; i <= ${#ROLES[@]}; i++ )); do
    worktree_path="${WORKTREE_PATHS[$i]}"
    if [[ "$worktree_path" == "$WORKING_DIR" ]]; then
      continue
    fi

    role_scripts_dir="$worktree_path/swarmforge/scripts"
    role_state_dir="$worktree_path/.swarmforge"
    mkdir -p "$role_scripts_dir"
    cp -R "$SCRIPT_DIR/." "$role_scripts_dir/"
    install_shared_constitution_articles "$worktree_path"
    mkdir -p "$role_state_dir/notify"
    cp "$SESSIONS_FILE" "$role_state_dir/sessions.tsv"
    cp "$TMUX_SOCKET_FILE" "$role_state_dir/tmux-socket"
    cp "$TMUX_ENV_FILE" "$role_state_dir/tmux-env"
    link_curator_skills "$worktree_path"
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

# Single read-modify-write over a worktree's .claude/settings.local.json. Always
# applies the ADR 0020 auto-compaction keys; also sets the ADR 0012 advisor model
# when a non-empty one is passed. One shared writer for both concerns (ADR 0020).
write_worktree_settings() {
  local worktree_path="$1"
  local advisor_model="${2:-}"
  local stop_script="${3:-}"
  local settings_dir="$worktree_path/.claude"
  local settings_file="$settings_dir/settings.local.json"

  mkdir -p "$settings_dir"
  SETTINGS_FILE="$settings_file" ADVISOR_MODEL="$advisor_model" STOP_SCRIPT="$stop_script" python3 -c '
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
advisor = os.environ.get("ADVISOR_MODEL", "")
if advisor:
  cfg["advisorModel"] = advisor
stop = os.environ.get("STOP_SCRIPT", "")
if stop:
  cfg.setdefault("hooks", {})
  cfg["hooks"]["Stop"] = [{"matcher": "", "hooks": [{"type": "command", "command": stop}]}]
with open(p, "w") as f: json.dump(cfg, f, indent=2)
  '
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

    # When bundling constitution.prompt, also include all articles so agents
    # don't re-read them at runtime following the "read every file in articles/" directive.
    if [[ "$file" == */constitution.prompt ]]; then
      local articles_dir="${WORKING_DIR}/swarmforge/constitution/articles"
      for article_file in "$articles_dir"/*.prompt(N); do
        local article_rel="${article_file#${WORKING_DIR}/}"
        [[ ${+seen[$article_rel]} -eq 0 ]] && queue+=("$article_file")
      done
    fi
  done

  printf '%s\n' "${bundle[@]}"
}

write_agent_instruction_file() {
  local role="$1"
  local prompt_file="$2"
  printf 'You are the %s in a SwarmForge multi-agent development swarm. Your full role, constitution, and operating instructions are in your swarm-persona skill. Invoke the swarm-persona skill at the start of every session and before responding to any handoff.\n' "$role" > "$prompt_file"
}

write_persona_skill_file() {
  local role="$1"
  local worktree="$2"
  local skill_dir="$worktree/.claude/skills/swarm-persona"
  local skill_file="$skill_dir/SKILL.md"
  typeset -a bundle_files=()
  local rel abs_path knowledge

  mkdir -p "$skill_dir"

  while IFS= read -r rel; do
    [[ -n "$rel" ]] && bundle_files+=("$rel")
  done < <(resolve_prompt_bundle "$role")

  {
    printf -- '---\nname: swarm-persona\ndescription: Load this agent'\''s SwarmForge role, constitution, and operating instructions\n---\n\n'
    printf '<swarmforge_agent_context role="%s">\n' "$role"
    printf '<instructions>\n'
    printf 'This prompt bundle is pre-resolved. Do not open or re-read any swarmforge/*.prompt files — all relevant instructions are already included below. Project knowledge files (AGENTS.md and your role file under .agents/roles/) are included below when present.\n'
    printf '</instructions>\n'
    for rel in "${bundle_files[@]}"; do
      abs_path="$WORKING_DIR/$rel"
      [[ -f "$abs_path" ]] || continue
      printf '<file path="%s">\n' "$rel"
      cat "$abs_path"
      printf '\n</file>\n'
    done
    for knowledge in "AGENTS.md" ".agents/roles/${role}.md"; do
      abs_path="$WORKING_DIR/$knowledge"
      [[ -f "$abs_path" ]] || continue
      printf '<file path="%s">\n' "$knowledge"
      cat "$abs_path"
      printf '\n</file>\n'
    done
    printf '</swarmforge_agent_context>\n'
  } > "$skill_file"
}

send_initial_prompt() {
  local session="$1"
  local display="$2"

  (
    sleep 3
    tmux -S "$TMUX_SOCKET" send-keys -t "$(tmux_agent_target "$session" "$display")" -l -- 'Invoke your swarm-persona skill to load your role and begin.'
    sleep 0.5
    tmux -S "$TMUX_SOCKET" send-keys -t "$(tmux_agent_target "$session" "$display")" C-m
  ) &!
}

launch_role() {
  local index="$1"
  local role="${ROLES[$index]}"
  local agent="${AGENTS[$index]}"
  local session="${SESSIONS[$index]}"
  local display="${DISPLAY_NAMES[$index]}"
  local role_worktree="${WORKTREE_PATHS[$index]}"
  local role_script_dir="$role_worktree/swarmforge/scripts"
  local prompt_file="$PROMPTS_DIR/${role}.md"
  local launch_cmd=""
  local role_model="${ROLE_MODELS[$index]}"
  local role_effort="${ROLE_EFFORTS[$index]}"
  local role_advisor="${ROLE_ADVISORS[$index]}"

  write_agent_instruction_file "$role" "$prompt_file"
  write_persona_skill_file "$role" "$role_worktree"

  if [[ "$role_worktree" == "$WORKING_DIR" ]]; then
    role_script_dir="$SCRIPT_DIR"
  fi

  case "$agent" in
    claude)
      write_worktree_settings "$role_worktree" "$role_advisor" "$role_script_dir/swarm-stop.sh"
      local claude_flags=""
      [[ -n "$role_model" ]]  && claude_flags+=" --model ${(q)role_model}"
      [[ -n "$role_effort" ]] && claude_flags+=" --effort ${(q)role_effort}"
      launch_cmd="export SWARMFORGE_ROLE='$role' && export PATH='$role_script_dir':\$PATH && cd '$role_worktree' && claude${claude_flags} --append-system-prompt-file '$prompt_file' --permission-mode auto -n 'SwarmForge ${display}'"
      ;;
    codex)
      [[ -n "$role_advisor" ]] && write_worktree_settings "$role_worktree" "$role_advisor"
      local codex_flags=""
      [[ -n "$role_model" ]] && codex_flags+=" -c model=${(q)role_model}"
      launch_cmd="export SWARMFORGE_ROLE='$role' && export PATH='$role_script_dir':\$PATH && cd '$role_worktree' && codex${codex_flags} -C '$role_worktree' \"\$(cat '$prompt_file')\""
      ;;
    copilot)
      [[ -n "$role_advisor" ]] && write_worktree_settings "$role_worktree" "$role_advisor"
      local copilot_flags=""
      [[ -n "$role_model" ]]  && copilot_flags+=" --model ${(q)role_model}"
      [[ -n "$role_effort" ]] && copilot_flags+=" --effort ${(q)role_effort}"
      launch_cmd="export SWARMFORGE_ROLE='$role' && export PATH='$role_script_dir':\$PATH && cd '$role_worktree' && copilot${copilot_flags} -C '$role_worktree' --name 'SwarmForge ${display}' -i \"\$(cat '$prompt_file')\""
      ;;
    grok)
      [[ -n "$role_advisor" ]] && write_worktree_settings "$role_worktree" "$role_advisor"
      local grok_flags=""
      [[ -n "$role_model" ]]  && grok_flags+=" --model ${(q)role_model}"
      [[ -n "$role_effort" ]] && grok_flags+=" --effort ${(q)role_effort}"
      launch_cmd="export SWARMFORGE_ROLE='$role' && export PATH='$role_script_dir':\$PATH && cd '$role_worktree' && grok${grok_flags} --cwd '$role_worktree' --permission-mode auto --rules \"\$(cat '$prompt_file')\""
      ;;
  esac

  if [[ "$index" -eq "${CLEANUP_OWNER_INDEX}" ]]; then
    launch_cmd="${launch_cmd}; exit_code=\$?; SWARMFORGE_TERMINAL_BACKEND='$TERMINAL_BACKEND' nohup '$SCRIPT_DIR/swarm-cleanup.sh' '$TMUX_SOCKET' '$WINDOW_IDS_FILE'"
    local session_name
    for session_name in "${SESSIONS[@]}"; do
      [[ -n "$session_name" ]] || continue
      launch_cmd+=" '$session_name'"
    done
    launch_cmd+=" >/dev/null 2>&1 &!; exit \$exit_code"
  fi

  tmux -S "$TMUX_SOCKET" send-keys -t "$(tmux_agent_target "$session" "$display")" "$launch_cmd" Enter
  echo -e "  ${CYAN}[${display}]${RESET} started in session ${session}"
}

install_skills() {
  local skills_src="$SCRIPT_DIR/../skills"
  local skills_dst="$WORKING_DIR/.claude/skills"
  local pins_file="$SCRIPT_DIR/install-pins.conf"

  [[ ! -f "$pins_file" ]] && return 0
  # shellcheck source=/dev/null
  source "$pins_file"

  echo -e "${CYAN}Installing skills...${RESET}"
  mkdir -p "$STATE_DIR" "$skills_dst"

  if [[ -d "$skills_src" ]]; then
    for skill_dir in "$skills_src"/*/; do
      local local_skill_name
      local_skill_name="$(basename "$skill_dir")"
      rm -rf "$skills_dst/$local_skill_name"
      cp -R "$skill_dir" "$skills_dst/$local_skill_name"
      echo -e "  ${GREEN}✓${RESET} $local_skill_name (local)"
    done
  fi

  local tmp_skills
  tmp_skills="$(mktemp -d)"
  local entire_url="https://github.com/entireio/skills/archive/${ENTIRE_SKILLS_SHA}.tar.gz"
  if curl -fsSL "$entire_url" | tar -xz --strip-components=1 -C "$tmp_skills" 2>/dev/null; then
    for skill_dir in "$tmp_skills/skills"/*/; do
      local skill_name
      skill_name="$(basename "$skill_dir")"
      rm -rf "$skills_dst/$skill_name"
      cp -R "$skill_dir" "$skills_dst/$skill_name"
    done
    rm -rf "$tmp_skills"
    echo -e "  ${GREEN}✓${RESET} entire skills (${ENTIRE_SKILLS_SHA:0:8})"
    printf '%s\n' "$ENTIRE_SKILLS_SHA" > "$STATE_DIR/skills-installed"
  else
    rm -rf "$tmp_skills"
    echo -e "  ${YELLOW}⚠${RESET} entire skills unavailable (no network?) — proceeding without them"
  fi
}

ensure_skills_installed() {
  local pins_file="$SCRIPT_DIR/install-pins.conf"
  [[ ! -f "$pins_file" ]] && return 0
  # shellcheck source=/dev/null
  source "$pins_file"
  local installed_sentinel="$STATE_DIR/skills-installed"
  if [[ -f "$installed_sentinel" ]] && [[ "$(< "$installed_sentinel")" == "$ENTIRE_SKILLS_SHA" ]]; then
    return 0
  fi
  install_skills
}

link_curator_skills() {
  local target_root="${1:-$WORKING_DIR}"
  local agents_skills_dir="$target_root/.agents/skills"
  local claude_skills_dir="$target_root/.claude/skills"
  [[ -d "$agents_skills_dir" ]] || return 0
  mkdir -p "$claude_skills_dir"
  for skill_dir in "$agents_skills_dir"/*/; do
    local skill_name
    skill_name="$(basename "$skill_dir")"
    [[ -e "$claude_skills_dir/$skill_name" ]] && continue
    ln -sfn "../../.agents/skills/$skill_name" "$claude_skills_dir/$skill_name"
  done
}

choose_cleanup_owner() {
  CLEANUP_OWNER_INDEX=1
}

check_dependency tmux
check_dependency git
detect_tmux_base_indexes
initialize_git_repo
ensure_runtime_git_excludes
install_shared_constitution_articles "$WORKING_DIR"
parse_config
check_backend_dependencies
ensure_skills_installed
link_curator_skills

if [[ ! -f "$STATE_DIR/setup-complete" ]]; then
  echo -e "${RED}Error:${RESET} project is not swarm-ready. Run /setup-swarm first." >&2
  exit 1
fi

prepare_workspace
prepare_worktrees
choose_cleanup_owner
TERMINAL_BACKEND="$(detect_terminal_backend)"
load_terminal_backend "$TERMINAL_BACKEND"

local_session=""
for local_session in "${SESSIONS[@]}"; do
  [[ -n "$local_session" ]] || continue
  if tmux -S "$TMUX_SOCKET" has-session -t "$local_session" 2>/dev/null; then
    echo -e "${YELLOW}Existing SwarmForge session found: ${local_session}. Killing it...${RESET}"
    tmux -S "$TMUX_SOCKET" kill-session -t "$local_session"
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
write_tmux_env_file
sync_worktree_scripts

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
echo -e "${GREEN}Tip: Write .swarmforge/notify/request, then run swarm-handoff while the swarm is running.${RESET}"
echo -e "${GREEN}Tip: Reattach manually with 'tmux -S $TMUX_SOCKET attach-session -t <session-name>' if needed.${RESET}"
echo ""

if terminal_backend_can_open_sessions; then
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
