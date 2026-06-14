#!/usr/bin/env zsh
set -euo pipefail

usage() {
  echo "Usage: complete-handoff.sh --file <accepted-queue-file>" >&2
}

QUEUE_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      [[ $# -ge 2 ]] || { usage; exit 1; }
      QUEUE_FILE="$2"
      shift 2
      ;;
    *)
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$QUEUE_FILE" || ! -f "$QUEUE_FILE" ]]; then
  echo "Accepted queue file not found: $QUEUE_FILE" >&2
  exit 1
fi

STATE_DIR="$PWD/.swarmforge/handoffs/queue"
ACCEPTED_DIR="$STATE_DIR/accepted"
COMPLETED_DIR="$STATE_DIR/completed"

mkdir -p "$COMPLETED_DIR"

queue_dir="$(cd "${QUEUE_FILE:h}" && pwd)"
accepted_dir="$(cd "$ACCEPTED_DIR" && pwd)"

if [[ "$queue_dir" != "$accepted_dir" ]]; then
  echo "Refusing to complete non-accepted queue file: $QUEUE_FILE" >&2
  exit 2
fi

base="${QUEUE_FILE:t}"
target="$COMPLETED_DIR/$base"
if [[ -e "$target" ]]; then
  target="$COMPLETED_DIR/$(date '+%Y%m%d-%H%M%S')-$base"
fi

mv "$QUEUE_FILE" "$target"
echo "COMPLETED $target"
