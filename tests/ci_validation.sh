#!/usr/bin/env zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

write_fake_command() {
  local command_path="$1"
  local body="$2"

  printf '%s\n' "$body" > "$command_path"
  chmod +x "$command_path"
}

test_opencode_backend_launches_with_generated_prompt() {
  local project_dir="$TMP_DIR/project"
  local fake_bin="$TMP_DIR/bin"
  local tmux_log="$TMP_DIR/tmux.log"

  mkdir -p "$project_dir/swarmforge" "$fake_bin"
  git -C "$project_dir" init -q

  printf '%s\n' "window architect opencode master" > "$project_dir/swarmforge/swarmforge.conf"
  printf '%s\n' "Project constitution." > "$project_dir/swarmforge/constitution.prompt"
  printf '%s\n' "Architect role." > "$project_dir/swarmforge/architect.prompt"

  write_fake_command "$fake_bin/tmux" '#!/usr/bin/env zsh
set -euo pipefail
print -r -- "$*" >> "$TMUX_LOG"
if [[ "${1:-}" == "has-session" ]]; then
  exit 1
fi
exit 0'

  write_fake_command "$fake_bin/opencode" '#!/usr/bin/env zsh
exit 0'

  ln -s /usr/bin/dirname "$fake_bin/dirname"
  ln -s /bin/mkdir "$fake_bin/mkdir"
  ln -s /bin/chmod "$fake_bin/chmod"
  ln -s /bin/cat "$fake_bin/cat"
  ln -s /bin/rm "$fake_bin/rm"
  ln -s /bin/zsh "$fake_bin/zsh"
  write_fake_command "$fake_bin/git" '#!/usr/bin/env zsh
exit 0'

  local output
  if ! output="$(PATH="$fake_bin" TMUX_LOG="$tmux_log" /bin/zsh "$ROOT_DIR/swarmforge.sh" "$project_dir" 2>&1)"; then
    echo "$output" >&2
    fail "swarmforge.sh should accept and launch the opencode backend"
  fi

  grep -Fq "opencode '$project_dir' --prompt" "$tmux_log" || {
    echo "tmux log:" >&2
    cat "$tmux_log" >&2
    fail "opencode launch command should target the role worktree with --prompt"
  }

  grep -Fq "cat '$project_dir/.swarmforge/prompts/architect.md'" "$tmux_log" || {
    echo "tmux log:" >&2
    cat "$tmux_log" >&2
    fail "opencode launch command should use the generated role prompt"
  }
}

test_opencode_backend_launches_with_generated_prompt

echo "ci_validation: all checks passed"
