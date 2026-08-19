package gitutil

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// initRepo creates a real throwaway git repo with two commits, so commit
// resolution edge cases (ambiguity, wrong object type) are exercised
// against real git behavior rather than mocked.
func initRepo(t *testing.T) (dir string, firstCommit, secondCommit string) {
	t.Helper()
	dir = t.TempDir()
	run := func(args ...string) string {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		cmd.Env = append(os.Environ(),
			"GIT_AUTHOR_NAME=test", "GIT_AUTHOR_EMAIL=test@example.com",
			"GIT_COMMITTER_NAME=test", "GIT_COMMITTER_EMAIL=test@example.com",
		)
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	run("init")
	if err := os.WriteFile(filepath.Join(dir, "a.txt"), []byte("one"), 0o644); err != nil {
		t.Fatal(err)
	}
	run("add", "a.txt")
	run("commit", "-m", "first")
	firstCommit = run("rev-parse", "HEAD")

	if err := os.WriteFile(filepath.Join(dir, "a.txt"), []byte("two"), 0o644); err != nil {
		t.Fatal(err)
	}
	run("add", "a.txt")
	run("commit", "-m", "second")
	secondCommit = run("rev-parse", "HEAD")

	return dir, firstCommit, secondCommit
}

func TestResolveCommitValid(t *testing.T) {
	dir, first, _ := initRepo(t)
	short, err := ResolveCommit(dir, first[:10])
	if err != nil {
		t.Fatalf("ResolveCommit: %v", err)
	}
	if !strings.HasPrefix(first, short) {
		t.Fatalf("resolved short hash %q is not a prefix of full hash %q", short, first)
	}
	if len(short) != 10 {
		t.Fatalf("expected a 10-character short hash, got %q", short)
	}
}

func TestResolveCommitUnknown(t *testing.T) {
	dir, _, _ := initRepo(t)
	if _, err := ResolveCommit(dir, "0000000000"); err == nil {
		t.Fatalf("expected an error for a nonexistent commit")
	}
}

func TestResolveCommitWrongObjectType(t *testing.T) {
	dir, _, _ := initRepo(t)
	// Resolve the tree object of HEAD instead of a commit.
	cmd := exec.Command("git", "rev-parse", "HEAD^{tree}")
	cmd.Dir = dir
	out, err := cmd.Output()
	if err != nil {
		t.Fatal(err)
	}
	tree := strings.TrimSpace(string(out))
	if _, err := ResolveCommit(dir, tree[:10]); err == nil {
		t.Fatalf("expected an error resolving a tree object as a commit")
	}
}

func TestEnsureRepoAndWorktree(t *testing.T) {
	dir := t.TempDir()
	if err := EnsureRepo(dir); err != nil {
		t.Fatalf("EnsureRepo: %v", err)
	}
	if err := EnsureRepo(dir); err != nil {
		t.Fatalf("EnsureRepo should be idempotent: %v", err)
	}

	worktreePath := filepath.Join(t.TempDir(), "wt")
	if err := AddWorktree(dir, worktreePath, "coder"); err != nil {
		t.Fatalf("AddWorktree: %v", err)
	}
	if _, err := os.Stat(filepath.Join(worktreePath, ".git")); err != nil {
		t.Fatalf("worktree not created: %v", err)
	}
	// idempotent: calling again on an existing worktree must not error
	if err := AddWorktree(dir, worktreePath, "coder"); err != nil {
		t.Fatalf("AddWorktree should be idempotent: %v", err)
	}
}

func TestEnsureIgnoredIsIdempotentAndPreservesExisting(t *testing.T) {
	dir := t.TempDir()
	if err := EnsureRepo(dir); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".gitignore"), []byte("node_modules/\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := EnsureIgnored(dir, ".swarmforge/", ".worktrees/"); err != nil {
		t.Fatalf("EnsureIgnored: %v", err)
	}
	if err := EnsureIgnored(dir, ".swarmforge/", ".worktrees/"); err != nil {
		t.Fatalf("EnsureIgnored should be idempotent: %v", err)
	}
	content, err := os.ReadFile(filepath.Join(dir, ".gitignore"))
	if err != nil {
		t.Fatal(err)
	}
	got := string(content)
	if !strings.Contains(got, "node_modules/") {
		t.Fatalf("existing content was not preserved:\n%s", got)
	}
	if strings.Count(got, ".swarmforge/") != 1 {
		t.Fatalf("entry was duplicated on second call:\n%s", got)
	}
}
