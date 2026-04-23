#!/usr/bin/env zsh
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: swarmlog.sh <role> <message>" >&2
  exit 1
fi

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$PROJECT_DIR/logs/agent_messages.log"

mkdir -p "$PROJECT_DIR/logs"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
MESSAGE="${*:2}"
echo "[$TIMESTAMP] [$1] $MESSAGE" >> "$LOG_FILE"
echo "[$1] $MESSAGE"
