#!/usr/bin/env zsh
set -euo pipefail

cat >&2 <<'EOF'
notify-agent.sh is obsolete.

Use the daemon-backed handoff protocol instead:
- swarm_handoff.sh <draft-file> to send work.
- ready_for_next_task.sh to accept work.
- done_with_current_task.sh to complete work.
EOF

exit 2
