// Package gitutil wraps the git subprocess calls SwarmForge needs:
// project-root discovery (including git-worktree awareness), commit
// abbreviation canonicalization, and repo/worktree setup.
package gitutil

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

func run(dir string, args ...string) (string, error) {
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.Output()
	return strings.TrimSpace(string(out)), err
}

// runCommit runs a git command that creates a commit, falling back to a
// synthetic author/committer identity when git has none configured (e.g. a
// fresh sandboxed environment) so EnsureRepo works without requiring the
// operator to have run `git config` first.
func runCommit(dir string, args ...string) (string, error) {
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(),
		"GIT_AUTHOR_NAME=SwarmForge", "GIT_AUTHOR_EMAIL=swarmforge@localhost",
		"GIT_COMMITTER_NAME=SwarmForge", "GIT_COMMITTER_EMAIL=swarmforge@localhost",
	)
	out, err := cmd.CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// Root returns the working tree's top-level directory (the worktree's own
// root, not the main repo's, when run inside a git worktree).
func Root(dir string) (string, error) {
	return run(dir, "rev-parse", "--show-toplevel")
}

// CommonDir returns the absolute path of the repo's common .git directory
// (shared across all worktrees of a repo).
func CommonDir(dir string) (string, error) {
	out, err := run(dir, "rev-parse", "--git-common-dir")
	if err != nil {
		return "", err
	}
	if filepath.IsAbs(out) {
		return filepath.Clean(out), nil
	}
	abs, err := filepath.Abs(filepath.Join(dir, out))
	if err != nil {
		return "", err
	}
	return abs, nil
}

// ResolveCommit canonicalizes a commit abbreviation: it must resolve to
// exactly one git object, and that object must be a commit. Returns the
// canonical 10-character short hash.
func ResolveCommit(repoDir, abbrev string) (string, error) {
	out, err := run(repoDir, "rev-parse", "--disambiguate="+abbrev)
	if err != nil {
		return "", fmt.Errorf("header 'commit' could not be resolved: %w", err)
	}
	matches := strings.Split(out, "\n")
	if len(matches) != 1 || matches[0] == "" {
		return "", fmt.Errorf("header 'commit' must resolve to exactly one Git object; '%s' matched %d", abbrev, len(matches))
	}
	object := matches[0]

	objectType, err := run(repoDir, "cat-file", "-t", object)
	if err != nil {
		return "", fmt.Errorf("could not determine type of object '%s': %w", object, err)
	}
	if objectType != "commit" {
		return "", fmt.Errorf("header 'commit' must resolve to a commit; '%s' resolves to '%s'", abbrev, objectType)
	}

	short, err := run(repoDir, "rev-parse", "--short=10", object)
	if err != nil {
		return "", fmt.Errorf("could not shorten object '%s': %w", object, err)
	}
	return short, nil
}

// EnsureRepo initializes a git repository at dir (with an initial commit)
// if one doesn't already exist there.
func EnsureRepo(dir string) error {
	if _, err := Root(dir); err == nil {
		return nil
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if _, err := run(dir, "init"); err != nil {
		return fmt.Errorf("git init failed: %w", err)
	}
	if _, err := runCommit(dir, "commit", "--allow-empty", "-m", "Initial commit"); err != nil {
		return fmt.Errorf("initial commit failed: %w", err)
	}
	return nil
}

// AddWorktree creates a git worktree at path on a new branch
// "swarmforge-<name>" tracking HEAD, unless one already exists there.
func AddWorktree(repoDir, path, name string) error {
	if _, err := os.Stat(filepath.Join(path, ".git")); err == nil {
		return nil
	}
	branch := "swarmforge-" + name
	if _, err := run(repoDir, "worktree", "add", "--force", "-B", branch, path, "HEAD"); err != nil {
		return fmt.Errorf("git worktree add failed for %s: %w", name, err)
	}
	return nil
}

// EnsureIgnored appends entries to .gitignore and .git/info/exclude if
// they aren't already present (idempotent, preserves existing content).
func EnsureIgnored(repoDir string, entries ...string) error {
	if err := appendMissingLines(filepath.Join(repoDir, ".gitignore"), entries); err != nil {
		return err
	}
	commonDir, err := CommonDir(repoDir)
	if err != nil {
		return err
	}
	return appendMissingLines(filepath.Join(commonDir, "info", "exclude"), entries)
}

func appendMissingLines(path string, entries []string) error {
	existing := map[string]bool{}
	if b, err := os.ReadFile(path); err == nil {
		for _, l := range strings.Split(string(b), "\n") {
			existing[strings.TrimSpace(l)] = true
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}

	var toAdd []string
	for _, e := range entries {
		if !existing[e] {
			toAdd = append(toAdd, e)
		}
	}
	if len(toAdd) == 0 {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()
	for _, e := range toAdd {
		if _, err := f.WriteString(e + "\n"); err != nil {
			return err
		}
	}
	return nil
}
