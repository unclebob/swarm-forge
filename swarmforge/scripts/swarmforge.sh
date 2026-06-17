#!/usr/bin/env zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec bb "$SCRIPT_DIR/swarmforge.bb" "$@"
