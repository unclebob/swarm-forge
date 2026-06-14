#!/usr/bin/env zsh

handoff_usage_role() {
  echo "Set SWARMFORGE_ROLE or pass --sender/--receiver." >&2
}

handoff_role_or_default() {
  local role="${1:-}"
  if [[ -n "$role" ]]; then
    echo "$role"
    return 0
  fi
  if [[ -n "${SWARMFORGE_ROLE:-}" ]]; then
    echo "$SWARMFORGE_ROLE"
    return 0
  fi
  handoff_usage_role
  return 1
}

handoff_state_dir() {
  echo "$PWD/.swarmforge/handoffs"
}

handoff_temp_file() {
  local dir
  dir="$(handoff_state_dir)/tmp"
  mkdir -p "$dir"
  mktemp "$dir/$1.XXXXXX"
}

handoff_logbook_file() {
  local dir="$PWD/.swarmforge"
  mkdir -p "$dir"
  echo "$dir/logbook.jsonl"
}

handoff_timestamp() {
  date '+%Y-%m-%dT%H:%M:%S%z'
}

handoff_id_timestamp() {
  date '+%Y%m%d-%H%M%S'
}

handoff_random_hex() {
  od -An -N3 -tx1 /dev/urandom | tr -d ' \n'
}

handoff_json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

handoff_append_logbook() {
  local direction="$1"
  local message="$2"
  local note="$3"
  local logbook
  logbook="$(handoff_logbook_file)"
  printf '{"timestamp":"%s","direction":"%s","message":"%s","note":"%s"}\n' \
    "$(handoff_json_escape "$(handoff_timestamp)")" \
    "$(handoff_json_escape "$direction")" \
    "$(handoff_json_escape "$message")" \
    "$(handoff_json_escape "$note")" >> "$logbook"
}

handoff_next_sequence() {
  local stream="$1"
  local state_dir seq_file last next
  state_dir="$(handoff_state_dir)/outgoing"
  mkdir -p "$state_dir"
  seq_file="$state_dir/$stream.seq"
  if [[ -f "$seq_file" ]]; then
    last="$(< "$seq_file")"
  else
    last=0
  fi
  if [[ ! "$last" == <-> ]]; then
    last=0
  fi
  next=$((last + 1))
  printf '%06d\n' "$next" > "$seq_file"
  printf '%06d' "$next"
}

handoff_message_id() {
  printf '%s-%s' "$(handoff_id_timestamp)" "$(handoff_random_hex)"
}

handoff_field() {
  local field="$1"
  local file="$2"
  local line
  line="$(grep -m 1 -E "^${field}: " "$file" || true)"
  if [[ -z "$line" ]]; then
    return 1
  fi
  printf '%s' "${line#*: }"
}

handoff_valid_priority() {
  local priority="$1"
  if [[ "$priority" == [0-9][0-9] ]]; then
    return 0
  fi
  return 1
}

handoff_valid_message_id() {
  local message_id="$1"
  if [[ "$message_id" == [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f] ]]; then
    return 0
  fi
  return 1
}

handoff_sequence_number() {
  local sequence="$1"
  echo $((10#$sequence))
}

handoff_sequence_range() {
  local start="$1"
  local end="$2"
  printf '%06d-%06d' "$start" "$end"
}

handoff_archive_sent() {
  local stream="$1"
  local sequence="$2"
  local message="$3"
  local dir
  dir="$(handoff_state_dir)/sent/$stream"
  mkdir -p "$dir"
  printf '%s' "$message" > "$dir/$sequence.txt"
}

handoff_archive_received() {
  local stream="$1"
  local sequence="$2"
  local message="$3"
  local archive_status="$4"
  local dir
  dir="$(handoff_state_dir)/received/$stream/$archive_status"
  mkdir -p "$dir"
  printf '%s' "$message" > "$dir/$sequence.txt"
}

handoff_last_received_file() {
  local stream="$1"
  local dir
  dir="$(handoff_state_dir)/incoming"
  mkdir -p "$dir"
  echo "$dir/$stream.seq"
}

handoff_queue_accepted() {
  local priority="$1"
  local stream="$2"
  local sequence="$3"
  local message="$4"
  local dir file
  dir="$(handoff_state_dir)/queue/accepted"
  mkdir -p "$dir"
  file="$dir/$priority-$(handoff_id_timestamp)-$stream-$sequence.txt"
  printf '%s' "$message" > "$file"
  echo "$file"
}

handoff_project_dir() {
  local git_common_dir candidate

  if git_common_dir=$(git -C "$PWD" rev-parse --git-common-dir 2>/dev/null); then
    if [[ "$git_common_dir" != /* ]]; then
      git_common_dir="$(cd "$PWD/$git_common_dir" && pwd)"
    fi
    candidate="${git_common_dir:h}"
    if [[ -f "$candidate/.swarmforge/sessions.tsv" ]]; then
      echo "$candidate"
      return 0
    fi
  fi

  echo "$PWD"
}

handoff_project_dir_from() {
  local start="$1"
  local git_common_dir candidate

  if git_common_dir=$(git -C "$start" rev-parse --git-common-dir 2>/dev/null); then
    if [[ "$git_common_dir" != /* ]]; then
      git_common_dir="$(cd "$start/$git_common_dir" && pwd)"
    fi
    candidate="${git_common_dir:h}"
    if [[ -f "$candidate/.swarmforge/sessions.tsv" ]]; then
      echo "$candidate"
      return 0
    fi
  fi

  echo "$start"
}

handoff_pending_dir() {
  local project_dir="$1"
  local role="$2"
  echo "$project_dir/.swarmforge/handoffs/queue/pending/$role"
}

handoff_busy_file() {
  local project_dir="$1"
  local role="$2"
  echo "$project_dir/.swarmforge/$role.busy"
}
