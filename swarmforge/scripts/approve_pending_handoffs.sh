#!/usr/bin/env bash
# Approve all pending SwarmForge specifier handoffs through the local
# dashboard API instead of the browser. Usage: approve_pending_handoffs.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

url_file="$ROOT/.swarmforge/dashboard-url"
if [ ! -f "$url_file" ]; then
  echo "No dashboard URL found at $url_file" >&2
  exit 1
fi
dashboard_url="$(cat "$url_file")"

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
  curl -fsS --max-time 15 -X POST \
    "$dashboard_url/api/approvals/$id/approve" || {
      echo "Failed to approve $id" >&2
      exit 1
    }
  approved=$((approved + 1))
done

if [ "$approved" -eq 0 ]; then
  echo "No pending approvals."
else
  echo "Approved $approved pending handoff(s)."
fi
