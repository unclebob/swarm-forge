package orchestrator

import (
	"path/filepath"
	"testing"

	"github.com/torratdev/swarmforge/internal/state"
)

// TestProjectRootDiscoverableFromWorktree pins down a load-bearing design
// decision: state.json is written only once, at the project root, never
// copied into each role's worktree. This only works because git worktrees
// of the same repo share a common .git dir, which state.FindProjectRoot
// walks back up through. If this test ever fails, every agent-facing
// command (handoff/ready/done) running from a dedicated worktree breaks.
func TestProjectRootDiscoverableFromWorktree(t *testing.T) {
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())

	if _, err := Prepare(root); err != nil {
		t.Fatalf("Prepare: %v", err)
	}

	cleanerPath := filepath.Join(root, ".worktrees", "cleaner")
	found, err := state.FindProjectRoot(cleanerPath)
	if err != nil {
		t.Fatalf("FindProjectRoot from a dedicated worktree failed: %v", err)
	}
	rootAbs, _ := filepath.Abs(root)
	foundAbs, _ := filepath.Abs(found)
	if rootAbs != foundAbs {
		t.Fatalf("FindProjectRoot from worktree = %q, want %q", found, root)
	}
}
