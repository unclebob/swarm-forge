package cli

import (
	"fmt"
	"os"
	"os/signal"
	"syscall"

	tea "github.com/charmbracelet/bubbletea"

	"github.com/torratdev/swarmforge/internal/orchestrator"
	"github.com/torratdev/swarmforge/internal/tui"
)

// RunUp prepares a swarm (validates swarmforge.yaml, creates git
// worktrees, patches Claude Code's trust dialog, writes state.json),
// launches every configured role's agent process under its own PTY, and
// takes over the terminal with the multi-pane TUI until the operator
// quits (leader+q) or the cleanup role's process exits.
//
// This function fundamentally needs a real terminal (bubbletea reads/
// writes os.Stdin/os.Stdout directly for the interactive session), so
// unlike the other command handlers it isn't practical to drive through
// Env's io.Writer abstraction end-to-end -- Prepare/Launch (the part that
// can go wrong non-interactively) are covered by internal/orchestrator's
// own tests instead.
func RunUp(env Env) int {
	result, err := orchestrator.Prepare(env.Cwd)
	if err != nil {
		fmt.Fprintln(env.Stderr, "swarmforge up:", err)
		return 1
	}

	run, err := orchestrator.Launch(env.Cwd, result.Project, result.State, orchestrator.StartDelay, nil)
	if err != nil {
		fmt.Fprintln(env.Stderr, "swarmforge up:", err)
		return 1
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
	programDone := make(chan struct{})
	go func() {
		select {
		case <-sigCh:
			run.Shutdown()
		case <-programDone:
		}
	}()

	roles := make([]string, len(result.Project.Roles))
	for i, r := range result.Project.Roles {
		roles[i] = r.Name
	}

	program := tea.NewProgram(tui.New(run, roles), tea.WithAltScreen())
	_, runErr := program.Run()
	close(programDone)

	// Idempotent via sync.Once: a no-op if the TUI (leader+q, or the
	// cleanup role exiting) or the signal handler above already ran it.
	run.Shutdown()

	if runErr != nil {
		fmt.Fprintln(env.Stderr, "swarmforge up:", runErr)
		return 1
	}
	return 0
}
