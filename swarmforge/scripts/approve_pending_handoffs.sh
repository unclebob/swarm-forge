#!/usr/bin/env bash
# Approve all pending SwarmForge specifier handoffs through the local
# pack-web approval logic instead of the browser. Usage:
# approve_pending_handoffs.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

pending_dir="$ROOT/.swarmforge/handoffs/pending_approval"
if [ ! -d "$pending_dir" ]; then
  echo "No pending approvals."
  exit 0
fi

approved=0
for file in "$pending_dir"/*.handoff; do
  [ -f "$file" ] || continue
  id="$(basename "$file" .handoff)"
  echo "Approving $id"
  bb -e "(load-file \"$ROOT/swarmforge/scripts/pack_web.bb\") (pack-web/approve! \"$ROOT\" \"$id\")"
  approved=$((approved + 1))
done

if [ "$approved" -eq 0 ]; then
  echo "No pending approvals."
else
  echo "Approved $approved pending handoff(s)."
fi
