// Package orchestrator implements "swarmforge up"'s startup sequencing:
// validating swarmforge.yaml, ensuring the git repo and per-role
// worktrees exist, pre-accepting Claude Code's trust dialog, and writing
// .swarmforge/state.json. Launching agent processes themselves is the
// PTY/TUI layer's job and is not implemented here yet.
package orchestrator

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/torratdev/swarmforge/internal/config"
	"github.com/torratdev/swarmforge/internal/gitutil"
	"github.com/torratdev/swarmforge/internal/state"
	"github.com/torratdev/swarmforge/internal/trust"
)

// Result summarizes what Prepare did, for "swarmforge up" to report.
type Result struct {
	Project          config.Project
	State            state.State
	TrustedDirs      int
	CreatedWorktrees []string
}

// Prepare validates projectRoot's swarmforge.yaml, ensures the git repo
// and its worktrees exist, patches Claude Code's trust dialog for every
// claude-agent role's worktree, creates each role's handoff directories,
// and writes .swarmforge/state.json.
func Prepare(projectRoot string) (Result, error) {
	proj, err := config.Load(projectRoot)
	if err != nil {
		return Result{}, fmt.Errorf("loading %s: %w", config.FileName, err)
	}

	promptExists := func(role string) bool {
		_, err := os.Stat(filepath.Join(projectRoot, "swarmforge", "roles", role+".prompt"))
		return err == nil
	}
	if errs := proj.Validate(promptExists); len(errs) != 0 {
		return Result{}, fmt.Errorf("invalid %s:\n- %s", config.FileName, strings.Join(errs, "\n- "))
	}

	if err := gitutil.EnsureRepo(projectRoot); err != nil {
		return Result{}, err
	}
	if err := gitutil.EnsureIgnored(projectRoot, ".swarmforge/", ".worktrees/"); err != nil {
		return Result{}, err
	}

	var created []string
	var roles []state.Role
	var claudeDirs []string
	for _, r := range proj.Roles {
		wtPath := r.WorktreePath(projectRoot)
		if !r.IsSharedWorktree() {
			if err := gitutil.AddWorktree(projectRoot, wtPath, r.Worktree); err != nil {
				return Result{}, err
			}
			created = append(created, wtPath)
		}
		if strings.ToLower(r.Agent) == "claude" {
			claudeDirs = append(claudeDirs, wtPath)
		}
		if err := ensureHandoffDirs(wtPath); err != nil {
			return Result{}, err
		}
		roles = append(roles, state.Role{
			Name:         r.Name,
			WorktreeName: r.Worktree,
			WorktreePath: wtPath,
			DisplayName:  r.DisplayName(),
			Agent:        r.Agent,
			ReceiveMode:  r.EffectiveReceiveMode(),
		})
	}

	trustedCount := 0
	if len(claudeDirs) > 0 {
		if home, err := os.UserHomeDir(); err == nil {
			dirs := append(append([]string{}, claudeDirs...), projectRoot)
			// A trust-dialog patch failure is non-fatal -- matches the
			// original implementation, which warns and continues rather
			// than failing the whole startup over an optional convenience.
			if n, err := trust.EnsureTrusted(home, dirs); err == nil {
				trustedCount = n
			}
		}
	}

	st := state.State{Roles: roles}
	if err := state.Save(projectRoot, st); err != nil {
		return Result{}, err
	}

	return Result{Project: proj, State: st, TrustedDirs: trustedCount, CreatedWorktrees: created}, nil
}

func ensureHandoffDirs(worktreePath string) error {
	base := filepath.Join(worktreePath, ".swarmforge", "handoffs")
	for _, d := range []string{
		filepath.Join(base, "outbox", "tmp"),
		filepath.Join(base, "sent"),
		filepath.Join(base, "failed"),
		filepath.Join(base, "inbox", "new"),
		filepath.Join(base, "inbox", "in_process"),
		filepath.Join(base, "inbox", "completed"),
	} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	return nil
}
