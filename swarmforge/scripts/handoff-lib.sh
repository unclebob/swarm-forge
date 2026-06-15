#!/usr/bin/env zsh

handoff_usage_role() {
  echo "Set SWARMFORGE_ROLE." >&2
}

handoff_role_or_default() {
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

handoff_inbox_dir() {
  echo "$(handoff_state_dir)/inbox"
}

handoff_project_root() {
  local git_common_dir worktree_root

  if worktree_root=$(git rev-parse --show-toplevel 2>/dev/null); then
    if [[ -f "$worktree_root/.swarmforge/roles.tsv" ]]; then
      echo "$worktree_root"
      return 0
    fi
  fi

  if git_common_dir=$(git rev-parse --git-common-dir 2>/dev/null); then
    if [[ "$git_common_dir" != /* ]]; then
      git_common_dir="$(cd "$git_common_dir" && pwd)"
    fi
    local common_parent="${git_common_dir:h}"
    if [[ -f "$common_parent/.swarmforge/roles.tsv" ]]; then
      echo "$common_parent"
      return 0
    fi
  fi

  echo "Cannot find SwarmForge project root" >&2
  return 1
}

handoff_roles_file() {
  local project_root
  project_root="$(handoff_project_root)" || return 1
  echo "$project_root/.swarmforge/roles.tsv"
}

handoff_role_known() {
  local role="$1" roles_file
  roles_file="$(handoff_roles_file)" || return 1
  awk -F '\t' -v role="$role" '$1 == role { found = 1 } END { exit !found }' "$roles_file"
}

handoff_role_worktree_name() {
  local role="$1" roles_file
  roles_file="$(handoff_roles_file)" || return 1
  awk -F '\t' -v role="$role" '$1 == role { print $2; found = 1 } END { exit !found }' "$roles_file"
}

handoff_timestamp() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

handoff_id_timestamp() {
  date -u '+%Y%m%dT%H%M%SZ'
}

handoff_valid_priority() {
  [[ "$1" == [0-9][0-9] ]]
}

handoff_header_field() {
  local field="$1"
  local file="$2"
  awk -v field="$field" '
    BEGIN { prefix = field ": " }
    $0 == "" { exit 1 }
    index($0, prefix) == 1 {
      print substr($0, length(prefix) + 1)
      found = 1
      exit 0
    }
    END { if (!found) exit 1 }
  ' "$file"
}

handoff_body() {
  awk 'seen { print } $0 == "" && !seen { seen = 1 }' "$1"
}

handoff_set_header() {
  local file="$1"
  local field="$2"
  local value="$3"
  local tmp

  tmp="$(mktemp "${file:h}/.headers.XXXXXX")"
  awk -v field="$field" -v value="$value" '
    BEGIN {
      prefix = field ": "
      inserted = 0
      replaced = 0
    }
    !inserted && $0 == "" {
      if (!replaced) {
        print prefix value
      }
      inserted = 1
      print
      next
    }
    !inserted && index($0, prefix) == 1 {
      print prefix value
      replaced = 1
      next
    }
    { print }
    END {
      if (!inserted && !replaced) {
        print prefix value
      }
    }
  ' "$file" > "$tmp"
  mv "$tmp" "$file"
}

handoff_print_task() {
  local file="$1"
  local from type priority

  from="$(handoff_header_field from "$file" || echo "unknown")"
  type="$(handoff_header_field type "$file" || echo "unknown")"
  priority="$(handoff_header_field priority "$file" || echo "50")"

  echo "TASK: $file"
  echo "FROM: $from"
  echo "TYPE: $type"
  echo "PRIORITY: $priority"
  echo "PAYLOAD:"
  handoff_body "$file"
}

handoff_next_sequence() {
  local dir seq_file last next
  dir="$(handoff_state_dir)"
  mkdir -p "$dir"
  seq_file="$dir/sequence"
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
