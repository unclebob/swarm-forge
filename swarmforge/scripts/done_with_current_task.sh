#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/handoff-lib.sh"

INBOX_DIR="$(handoff_inbox_dir)"
IN_PROCESS_DIR="$INBOX_DIR/in_process"
COMPLETED_DIR="$INBOX_DIR/completed"

mkdir -p "$IN_PROCESS_DIR" "$COMPLETED_DIR"

in_process_files=("$IN_PROCESS_DIR"/*.handoff(N))
if (( ${#in_process_files[@]} == 0 )); then
  echo "NO_CURRENT_TASK" >&2
  exit 1
fi

if (( ${#in_process_files[@]} > 1 )); then
  echo "AMBIGUOUS_TASK_STATE: multiple tasks are in process." >&2
  for file in "${in_process_files[@]}"; do
    echo "- $file" >&2
  done
  exit 2
fi

source_file="${in_process_files[1]}"
handoff_set_header "$source_file" "completed_at" "$(handoff_timestamp)"

target_file="$COMPLETED_DIR/${source_file:t}"
if [[ -e "$target_file" ]]; then
  echo "AMBIGUOUS_TASK_STATE: completed file already exists: $target_file" >&2
  exit 2
fi

mv "$source_file" "$target_file"
echo "COMPLETED: $target_file"
exec "$SCRIPT_DIR/ready_for_next_task.sh"
