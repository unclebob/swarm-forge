// Command swarmforge is the single binary that replaces every Babashka
// script and shell wrapper from the original implementation. It is also
// installed under three extra PATH-visible names
// (swarm_handoff.sh/ready_for_next.sh/done_with_current.sh, see
// internal/cli/shim.go) so role prompts written for the old scripts keep
// working unmodified: main dispatches on argv[0]'s basename before falling
// through to normal subcommand parsing.
package main

import (
	"fmt"
	"os"
	"time"

	"github.com/spf13/cobra"
	"github.com/torratdev/swarmforge/internal/cli"
)

func realEnv() cli.Env {
	cwd, err := os.Getwd()
	if err != nil {
		fmt.Fprintln(os.Stderr, "swarmforge: could not determine working directory:", err)
		os.Exit(1)
	}
	return cli.Env{
		Cwd:    cwd,
		Stdout: os.Stdout,
		Stderr: os.Stderr,
		Getenv: os.Getenv,
		Now:    time.Now,
	}
}

func main() {
	if code, handled := cli.DispatchShim(os.Args, realEnv()); handled {
		os.Exit(code)
	}

	root := &cobra.Command{
		Use:           "swarmforge",
		Short:         "Orchestrate a swarm of AI coding-agent CLIs across git worktrees",
		SilenceUsage:  true,
		SilenceErrors: true,
	}

	root.AddCommand(handoffCmd(), readyCmd(), doneCmd())

	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "swarmforge:", err)
		os.Exit(1)
	}
}

func handoffCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "handoff <draft-file>",
		Short: "Validate a draft handoff and queue it into the outbox",
		RunE: func(cmd *cobra.Command, args []string) error {
			return exitCode(cli.RunHandoff(realEnv(), args))
		},
	}
}

func readyCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "ready",
		Short: "Accept the next task or batch for the current role",
		RunE: func(cmd *cobra.Command, args []string) error {
			return exitCode(cli.RunReady(realEnv()))
		},
	}
}

func doneCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "done",
		Short: "Complete the current task or batch and report the next one",
		RunE: func(cmd *cobra.Command, args []string) error {
			return exitCode(cli.RunDone(realEnv()))
		},
	}
}

// exitCode turns a command handler's process exit code into the
// os.Exit call main performs after root.Execute returns, without cobra
// printing an extra "Error:" line for what are really just non-zero exit
// statuses, not usage errors.
func exitCode(code int) error {
	if code == 0 {
		return nil
	}
	os.Exit(code)
	return nil
}
