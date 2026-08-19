package cli

import (
	"fmt"
	"strings"

	"github.com/torratdev/swarmforge/internal/orchestrator"
)

// RunUp prepares a swarm: validates swarmforge.yaml, creates git
// worktrees, patches Claude Code's trust dialog, and writes
// .swarmforge/state.json. It does not launch any agent processes yet --
// that requires the PTY/TUI layer, which is not implemented as of this
// phase of the rewrite.
func RunUp(env Env) int {
	result, err := orchestrator.Prepare(env.Cwd)
	if err != nil {
		fmt.Fprintln(env.Stderr, "swarmforge up:", err)
		return 1
	}

	fmt.Fprintf(env.Stdout, "Prepared %d role(s):\n", len(result.State.Roles))
	for _, r := range result.State.Roles {
		fmt.Fprintf(env.Stdout, "  - %s (%s, %s, %s)\n", r.Name, r.Agent, r.ReceiveMode, r.WorktreePath)
	}
	if len(result.CreatedWorktrees) > 0 {
		fmt.Fprintln(env.Stdout, "Worktrees created:", strings.Join(result.CreatedWorktrees, ", "))
	}
	if result.TrustedDirs > 0 {
		fmt.Fprintf(env.Stdout, "Pre-accepted the Claude Code trust dialog for %d director%s.\n", result.TrustedDirs, plural(result.TrustedDirs))
	}
	fmt.Fprintln(env.Stdout, "Agent launch is not implemented yet in this build; the swarm has been prepared but no agents were started.")
	return 0
}

func plural(n int) string {
	if n == 1 {
		return "y"
	}
	return "ies"
}
