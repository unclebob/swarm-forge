#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  echo "Usage: notify-agent.sh" >&2
  echo "Usage: notify-agent.sh send <target-role> --file <body-file> [--sender <sender-role>] [--priority NN]" >&2
  echo "       notify-agent.sh receive --file <message-file> [--receiver <receiver-role>]" >&2
  echo "       notify-agent.sh complete --file <accepted-queue-file>" >&2
  echo "       notify-agent.sh <target-role-or-index> --file <message-file>" >&2
}

find_project_dir() {
  local git_common_dir worktree_root

  if worktree_root=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null); then
    if [[ -f "$worktree_root/.swarmforge/sessions.tsv" && -f "$worktree_root/.swarmforge/tmux-socket" ]]; then
      echo "$worktree_root"
      return 0
    fi
  fi

  if git_common_dir=$(git -C "$SCRIPT_DIR" rev-parse --git-common-dir 2>/dev/null); then
    if [[ "$git_common_dir" != /* ]]; then
      git_common_dir="$(cd "$SCRIPT_DIR/$git_common_dir" && pwd)"
    fi
    local project_dir="${git_common_dir:h}"
    if [[ -f "$project_dir/.swarmforge/sessions.tsv" ]]; then
      echo "$project_dir"
      return 0
    fi
  fi

  echo "${SCRIPT_DIR:h:h}"
}

request_field() {
  local field="$1"
  local file="$2"
  local line

  line="$(grep -m 1 -E "^${field}: " "$file" || true)"
  [[ -n "$line" ]] || return 1
  printf '%s\n' "${line#*: }"
}

safe_request_path() {
  local path="$1"

  [[ -n "$path" ]] || return 1
  [[ "$path" != /* ]] || return 1
  [[ "$path" != "." ]] || return 1
  [[ "$path" != ".." ]] || return 1
  [[ "$path" != "../"* ]] || return 1
  [[ "$path" != *"/../"* ]] || return 1
  [[ "$path" != *"/.." ]] || return 1
  return 0
}

run_request_file() {
  local project_dir request_file command target file priority sender receiver archive_dir archive_file exit_status
  local -a args

  project_dir="$(find_project_dir)"
  request_file="$project_dir/.swarmforge/notify/request"
  archive_dir="$project_dir/.swarmforge/notify/archive"

  if [[ ! -f "$request_file" ]]; then
    echo "Notify request file not found: $request_file" >&2
    exit 1
  fi

  command="$(request_field command "$request_file")" || {
    echo "Notify request missing command: $request_file" >&2
    exit 1
  }
  file="$(request_field file "$request_file")" || {
    echo "Notify request missing file: $request_file" >&2
    exit 1
  }
  priority="$(request_field priority "$request_file" || true)"
  sender="$(request_field sender "$request_file" || true)"
  receiver="$(request_field receiver "$request_file" || true)"

  if ! safe_request_path "$file"; then
    echo "Notify request file must be a safe relative path: $file" >&2
    exit 1
  fi

  case "$command" in
    send)
      target="$(request_field target "$request_file")" || {
        echo "Notify send request missing target: $request_file" >&2
        exit 1
      }
      args=("$SCRIPT_DIR/send-handoff.sh" "$target" "--file" "$file")
      [[ -z "$sender" ]] || args+=("--sender" "$sender")
      [[ -z "$priority" ]] || args+=("--priority" "$priority")
      ;;
    receive)
      args=("$SCRIPT_DIR/receive-handoff.sh" "--file" "$file")
      [[ -z "$receiver" ]] || args+=("--receiver" "$receiver")
      ;;
    complete)
      args=("$SCRIPT_DIR/complete-handoff.sh" "--file" "$file")
      ;;
    *)
      echo "Unknown notify request command: $command" >&2
      exit 1
      ;;
  esac

  set +e
  "${args[@]}"
  exit_status=$?
  set -e

  if [[ "$exit_status" -eq 0 ]]; then
    mkdir -p "$archive_dir"
    archive_file="$archive_dir/$(date '+%Y%m%d-%H%M%S')-$$.request"
    mv "$request_file" "$archive_file"
    echo "Archived notify request: $archive_file"
  fi

  exit "$exit_status"
}

if [[ $# -eq 0 ]]; then
  run_request_file
fi

if [[ $# -gt 0 ]]; then
  case "$1" in
    send)
      shift
      exec "$SCRIPT_DIR/send-handoff.sh" "$@"
      ;;
    receive)
      shift
      exec "$SCRIPT_DIR/receive-handoff.sh" "$@"
      ;;
    resend)
      shift
      exec "$SCRIPT_DIR/resend-handoff.sh" "$@"
      ;;
    complete)
      shift
      exec "$SCRIPT_DIR/complete-handoff.sh" "$@"
      ;;
  esac
fi

if [[ $# -ne 3 || "${2:-}" != "--file" ]]; then
  usage
  exit 1
fi

TARGET="$1"
MESSAGE_FILE="$3"

PROJECT_DIR="$(find_project_dir)"
SESSIONS_FILE="$PROJECT_DIR/.swarmforge/sessions.tsv"
TMUX_SOCKET_FILE="$PROJECT_DIR/.swarmforge/tmux-socket"
TMUX_ENV_FILE="$PROJECT_DIR/.swarmforge/tmux-env"

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

TARGET_SESSION=$(resolve_session "$TARGET") || {
  echo "Unknown target: $TARGET" >&2
  exit 1
}

if [[ ! -f "$MESSAGE_FILE" ]]; then
  echo "Message file not found: $MESSAGE_FILE" >&2
  exit 1
fi
MESSAGE="$(< "$MESSAGE_FILE")"

if [[ -z "${TMUX:-}" && -f "$TMUX_ENV_FILE" ]]; then
  TMUX="$(< "$TMUX_ENV_FILE")"
  export TMUX
fi

if [[ -n "${TMUX:-}" ]]; then
  tmux send-keys -t "$TARGET_SESSION" -l -- "$MESSAGE"
  sleep 0.15
  tmux send-keys -t "$TARGET_SESSION" C-m
  sleep 0.05
  tmux send-keys -t "$TARGET_SESSION" C-j
else
  if [[ ! -f "$TMUX_SOCKET_FILE" ]]; then
    echo "Tmux socket file not found: $TMUX_SOCKET_FILE" >&2
    exit 1
  fi

  TMUX_SOCKET="$(< "$TMUX_SOCKET_FILE")"
  tmux -S "$TMUX_SOCKET" send-keys -t "$TARGET_SESSION" -l -- "$MESSAGE"
  sleep 0.15
  tmux -S "$TMUX_SOCKET" send-keys -t "$TARGET_SESSION" C-m
  sleep 0.05
  tmux -S "$TMUX_SOCKET" send-keys -t "$TARGET_SESSION" C-j
fi
