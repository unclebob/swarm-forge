package orchestrator

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/torratdev/swarmforge/internal/pack"
	"github.com/torratdev/swarmforge/internal/state"
)

func scaffoldTwoPack(t *testing.T) string {
	t.Helper()
	def, err := pack.Load("two-pack")
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	if err := pack.Generate(def, "two-pack", dir); err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestPrepareCreatesWorktreesAndState(t *testing.T) {
	root := scaffoldTwoPack(t)
	home := t.TempDir()
	t.Setenv("HOME", home)

	result, err := Prepare(root)
	if err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	if len(result.State.Roles) != 2 {
		t.Fatalf("expected 2 roles in state, got %+v", result.State.Roles)
	}

	// coder is "master": no dedicated worktree.
	coderPath := filepath.Join(root, ".worktrees", "coder")
	if _, err := os.Stat(coderPath); !os.IsNotExist(err) {
		t.Fatalf("master-worktree role should not get a .worktrees/ dir")
	}
	// cleaner has its own worktree.
	cleanerPath := filepath.Join(root, ".worktrees", "cleaner")
	if _, err := os.Stat(filepath.Join(cleanerPath, ".git")); err != nil {
		t.Fatalf("cleaner worktree not created: %v", err)
	}
	found := false
	for _, w := range result.CreatedWorktrees {
		if w == cleanerPath {
			found = true
		}
	}
	if !found {
		t.Fatalf("cleaner worktree not reported as created: %v", result.CreatedWorktrees)
	}

	loaded, err := state.Load(root)
	if err != nil {
		t.Fatalf("state.Load: %v", err)
	}
	if !loaded.RoleKnown("coder") || !loaded.RoleKnown("cleaner") {
		t.Fatalf("state.json missing expected roles: %+v", loaded)
	}

	for _, d := range []string{
		filepath.Join(root, ".swarmforge", "handoffs", "outbox", "tmp"),
		filepath.Join(cleanerPath, ".swarmforge", "handoffs", "inbox", "new"),
	} {
		if _, err := os.Stat(d); err != nil {
			t.Fatalf("expected handoff dir %s to exist: %v", d, err)
		}
	}
}

func TestPrepareIsIdempotent(t *testing.T) {
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())

	if _, err := Prepare(root); err != nil {
		t.Fatalf("first Prepare: %v", err)
	}
	if _, err := Prepare(root); err != nil {
		t.Fatalf("second Prepare should also succeed: %v", err)
	}
}

func TestPrepareRejectsInvalidConfig(t *testing.T) {
	root := scaffoldTwoPack(t)
	// Corrupt the config: duplicate a role name.
	if err := os.WriteFile(filepath.Join(root, "swarmforge.yaml"), []byte("roles:\n  - name: coder\n    agent: claude\n    worktree: master\n  - name: coder\n    agent: claude\n    worktree: second\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Prepare(root); err == nil {
		t.Fatalf("expected Prepare to reject a config with duplicate role names")
	}
}

func TestPrepareEnsuresGitignoreEntries(t *testing.T) {
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())
	if _, err := Prepare(root); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(root, ".gitignore"))
	if err != nil {
		t.Fatal(err)
	}
	got := string(content)
	for _, want := range []string{".swarmforge/", ".worktrees/"} {
		if !strings.Contains(got, want) {
			t.Fatalf(".gitignore missing %q:\n%s", want, got)
		}
	}
}
