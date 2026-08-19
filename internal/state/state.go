// Package state manages SwarmForge's per-project runtime state:
// .swarmforge/state.json (the role registry, replacing the old
// roles.tsv/sessions.tsv/tmux-socket/tmux-env files) and project-root
// discovery so helper commands work from any role's worktree.
package state

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"github.com/torratdev/swarmforge/internal/gitutil"
)

// Role is one configured swarm participant.
type Role struct {
	Name         string `json:"name"`
	WorktreeName string `json:"worktree_name"`
	WorktreePath string `json:"worktree_path"`
	DisplayName  string `json:"display_name"`
	Agent        string `json:"agent"`
	ReceiveMode  string `json:"receive_mode"`
}

// State is the full role registry for a running (or configured) swarm.
type State struct {
	Version int    `json:"version"`
	Roles   []Role `json:"roles"`
}

const CurrentVersion = 1

// RelPath is state.json's location relative to a project root.
const RelPath = ".swarmforge/state.json"

// RoleByName looks up a role by exact name.
func (s State) RoleByName(name string) (Role, bool) {
	for _, r := range s.Roles {
		if r.Name == name {
			return r, true
		}
	}
	return Role{}, false
}

// RoleKnown reports whether name is a configured role.
func (s State) RoleKnown(name string) bool {
	_, ok := s.RoleByName(name)
	return ok
}

// Load reads state.json from a project root.
func Load(projectRoot string) (State, error) {
	b, err := os.ReadFile(filepath.Join(projectRoot, RelPath))
	if err != nil {
		return State{}, err
	}
	var s State
	if err := json.Unmarshal(b, &s); err != nil {
		return State{}, fmt.Errorf("parsing %s: %w", RelPath, err)
	}
	return s, nil
}

// Save writes state.json to a project root, creating .swarmforge/ if
// needed.
func Save(projectRoot string, s State) error {
	s.Version = CurrentVersion
	b, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	path := filepath.Join(projectRoot, RelPath)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

// FindProjectRoot locates the SwarmForge project root from cwd: it tries
// cwd itself, then the git worktree's top-level directory, then (for a
// role running inside a git worktree) the parent of the repo's common .git
// directory -- mirroring the original project-root() lookup so helper
// commands work whether invoked from the main working directory or from
// any role's own worktree.
func FindProjectRoot(cwd string) (string, error) {
	if hasState(cwd) {
		return cwd, nil
	}
	if root, err := gitutil.Root(cwd); err == nil && hasState(root) {
		return root, nil
	}
	if common, err := gitutil.CommonDir(cwd); err == nil {
		candidate := filepath.Dir(common)
		if hasState(candidate) {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("cannot find SwarmForge project root (no %s found from %s)", RelPath, cwd)
}

func hasState(dir string) bool {
	_, err := os.Stat(filepath.Join(dir, RelPath))
	return err == nil
}
