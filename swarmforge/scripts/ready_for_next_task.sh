#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/handoff-lib.sh"

INBOX_DIR="$(handoff_inbox_dir)"
NEW_DIR="$INBOX_DIR/new"
IN_PROCESS_DIR="$INBOX_DIR/in_process"
COMPLETED_DIR="$INBOX_DIR/completed"

mkdir -p "$NEW_DIR" "$IN_PROCESS_DIR" "$COMPLETED_DIR"

in_process_files=("$IN_PROCESS_DIR"/*.handoff(N))
if (( ${#in_process_files[@]} > 1 )); then
  echo "AMBIGUOUS_TASK_STATE: multiple tasks are already in process." >&2
  for file in "${in_process_files[@]}"; do
    echo "- $file" >&2
  done
  exit 2
fi

if (( ${#in_process_files[@]} == 1 )); then
  handoff_print_task "${in_process_files[1]}"
  exit 0
fi

new_files=("$NEW_DIR"/*.handoff(N))
if (( ${#new_files[@]} == 0 )); then
  echo "NO_TASK"
  exit 0
fi

new_files=("${(@on)new_files}")
source_file="${new_files[1]}"
target_file="$IN_PROCESS_DIR/${source_file:t}"

if [[ -e "$target_file" ]]; then
  echo "AMBIGUOUS_TASK_STATE: target in-process file already exists: $target_file" >&2
  exit 2
fi

mv "$source_file" "$target_file"
handoff_set_header "$target_file" "dequeued_at" "$(handoff_timestamp)"
handoff_print_task "$target_file"
