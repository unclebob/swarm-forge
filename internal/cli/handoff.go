package cli

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/torratdev/swarmforge/internal/gitutil"
	"github.com/torratdev/swarmforge/internal/handoff"
	"github.com/torratdev/swarmforge/internal/state"
)

const draftUsage = `Usage: swarmforge handoff <draft-file>

Draft formats:

type: git_handoff
to: <role>[,<role>...]
priority: NN
task: <short-stable-task-name>
commit: <10-char-commit-abbrev>

type: note
to: <role>[,<role>...]
priority: NN
message: <one line, max 80 chars>`

// RunHandoff validates a draft handoff file and, if valid, queues it into
// the sender's outbox. It is the Go equivalent of swarm_handoff.bb.
func RunHandoff(env Env, args []string) int {
	if len(args) != 1 {
		fmt.Fprintln(env.Stderr, draftUsage)
		return 1
	}
	draftPath := args[0]

	sender := env.Getenv("SWARMFORGE_ROLE")
	if sender == "" {
		fmt.Fprintln(env.Stderr, "Set SWARMFORGE_ROLE.")
		return 1
	}

	content, err := os.ReadFile(draftPath)
	if err != nil {
		fmt.Fprintln(env.Stderr, "Draft file not found:", draftPath)
		return 1
	}

	projectRoot, err := state.FindProjectRoot(env.Cwd)
	if err != nil {
		fmt.Fprintln(env.Stderr, err)
		return 1
	}
	st, err := state.Load(projectRoot)
	if err != nil {
		fmt.Fprintln(env.Stderr, "Could not load project state:", err)
		return 1
	}
	if !st.RoleKnown(sender) {
		fmt.Fprintln(env.Stderr, "Unknown sender role:", sender)
		return 1
	}

	parsed := handoff.ParseDraft(string(content))
	resolveCommit := func(abbrev string) (string, error) {
		return gitutil.ResolveCommit(env.Cwd, abbrev)
	}
	validation := handoff.ValidateDraft(parsed.Headers, st.RoleKnown, resolveCommit)

	allErrors := append(append([]string{}, parsed.Errors...), validation.Errors...)
	if len(allErrors) > 0 {
		fmt.Fprintln(env.Stderr, "HANDOFF INVALID:", draftPath)
		fmt.Fprintln(env.Stderr)
		fmt.Fprintln(env.Stderr, "Errors:")
		for _, e := range allErrors {
			fmt.Fprintln(env.Stderr, "-", e)
		}
		fmt.Fprintln(env.Stderr)
		fmt.Fprintln(env.Stderr, draftUsage)
		return 2
	}

	handoffsDir := filepath.Join(env.Cwd, ".swarmforge", "handoffs")
	outboxFile, err := handoff.WriteDraft(handoffsDir, parsed.Headers, validation.Recipients, sender, validation.CanonicalCommit, env.Now())
	if err != nil {
		fmt.Fprintln(env.Stderr, "Could not queue handoff:", err)
		return 1
	}
	if err := os.Remove(draftPath); err != nil {
		fmt.Fprintln(env.Stderr, "Warning: could not remove draft file:", err)
	}
	fmt.Fprintln(env.Stdout, "HANDOFF QUEUED:", outboxFile)
	return 0
}
