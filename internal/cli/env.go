// Package cli implements SwarmForge's command handlers: the operator-facing
// "up"/"down"/"init" commands and the agent-facing "handoff"/"ready"/"done"
// helper commands that agent CLIs invoke as subprocesses during their own
// tool-use loops.
package cli

import (
	"io"
	"time"
)

// Env bundles the ambient state a command handler needs, so handlers are
// pure functions of their arguments and can be unit tested without a real
// process environment.
type Env struct {
	Cwd    string
	Stdout io.Writer
	Stderr io.Writer
	Getenv func(string) string
	Now    func() time.Time
}
