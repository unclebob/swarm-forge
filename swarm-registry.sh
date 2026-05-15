#!/usr/bin/env zsh
# swarm-registry.sh — manage ~/.swarmforge/registry.json
#
# Sourceable helper used by swarmforge.sh (registry_add) and
# swarm-cleanup.sh (registry_remove). All operations are guarded by an
# mkdir-based lock so concurrent swarm launches across different
# working directories cannot clobber each other's writes.
#
# Registry shape:
#   { "swarms": [ { "workingDirectory": "...", "instanceId": "...", "startedAt": "..." }, ... ] }
#
# (workingDirectory, instanceId) is the composite unique key — a swarm
# overwrites any existing entry for the same directory + instance on
# registry_add. Multiple instances per project coexist as separate entries.

set -u

REGISTRY_DIR="$HOME/.swarmforge"
REGISTRY_FILE="$REGISTRY_DIR/registry.json"
REGISTRY_LOCK="$REGISTRY_DIR/registry.lock"
REGISTRY_LOCK_MAX_ATTEMPTS=50
REGISTRY_LOCK_SLEEP=0.1

registry_init() {
  mkdir -p "$REGISTRY_DIR"
  if [[ ! -f "$REGISTRY_FILE" ]]; then
    printf '{"swarms":[]}\n' > "$REGISTRY_FILE"
    return
  fi
  if ! jq empty "$REGISTRY_FILE" >/dev/null 2>&1; then
    echo "swarm-registry: $REGISTRY_FILE is not valid JSON; reinitializing" >&2
    printf '{"swarms":[]}\n' > "$REGISTRY_FILE"
  fi
}

registry_lock_acquire() {
  mkdir -p "$REGISTRY_DIR"
  local attempts=0
  while ! mkdir "$REGISTRY_LOCK" 2>/dev/null; do
    attempts=$((attempts + 1))
    if (( attempts > REGISTRY_LOCK_MAX_ATTEMPTS )); then
      echo "swarm-registry: failed to acquire $REGISTRY_LOCK after $attempts attempts" >&2
      return 1
    fi
    sleep "$REGISTRY_LOCK_SLEEP"
  done
}

registry_lock_release() {
  rmdir "$REGISTRY_LOCK" 2>/dev/null || true
}

registry_add() {
  local working_dir="$1"
  local instance_id="${2:-}"
  if [[ -z "$instance_id" ]]; then
    echo "swarm-registry: registry_add requires <working_dir> <instance_id>" >&2
    return 1
  fi
  local started_at
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  registry_lock_acquire || return 1
  registry_init

  local tmp="${REGISTRY_FILE}.tmp.$$"
  if jq --arg dir "$working_dir" \
        --arg instance "$instance_id" \
        --arg started "$started_at" \
        '.swarms |= ((map(select(.workingDirectory != $dir or .instanceId != $instance))) + [{workingDirectory: $dir, instanceId: $instance, startedAt: $started}])' \
        "$REGISTRY_FILE" > "$tmp"; then
    mv "$tmp" "$REGISTRY_FILE"
  else
    rm -f "$tmp"
    registry_lock_release
    echo "swarm-registry: failed to write entry for $working_dir ($instance_id)" >&2
    return 1
  fi

  registry_lock_release
}

registry_remove() {
  local working_dir="$1"
  local instance_id="${2:-}"
  if [[ -z "$instance_id" ]]; then
    echo "swarm-registry: registry_remove requires <working_dir> <instance_id>" >&2
    return 1
  fi

  registry_lock_acquire || return 1

  if [[ ! -f "$REGISTRY_FILE" ]]; then
    registry_lock_release
    return 0
  fi

  local tmp="${REGISTRY_FILE}.tmp.$$"
  if jq --arg dir "$working_dir" \
        --arg instance "$instance_id" \
        '.swarms |= map(select(.workingDirectory != $dir or .instanceId != $instance))' \
        "$REGISTRY_FILE" > "$tmp"; then
    mv "$tmp" "$REGISTRY_FILE"
  else
    rm -f "$tmp"
    registry_lock_release
    echo "swarm-registry: failed to remove entry for $working_dir ($instance_id)" >&2
    return 1
  fi

  registry_lock_release
}
