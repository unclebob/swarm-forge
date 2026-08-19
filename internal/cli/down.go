package cli

import (
	"fmt"
	"syscall"
	"time"

	"github.com/TorratDev/swarm-forge/internal/orchestrator"
)

// RunDown stops a swarm running in another process: it reads the PID
// orchestrator.Launch recorded in .swarmforge/swarmforge.pid, sends it
// SIGTERM (that process's own signal handler runs the same Shutdown a
// leader+q quit or a cleanup-role exit would), waits for it to exit, and
// escalates to SIGKILL if it doesn't within orchestrator.CloseTimeout.
func RunDown(env Env, projectRoot string) int {
	pid, err := orchestrator.ReadPID(projectRoot)
	if err != nil {
		fmt.Fprintln(env.Stderr, "swarmforge down: no running swarm found:", err)
		return 1
	}

	if err := syscall.Kill(pid, syscall.SIGTERM); err != nil {
		fmt.Fprintln(env.Stderr, "swarmforge down: signaling pid", pid, "failed:", err)
		return 1
	}

	deadline := time.Now().Add(orchestrator.CloseTimeout)
	for time.Now().Before(deadline) {
		if syscall.Kill(pid, 0) != nil {
			fmt.Fprintln(env.Stdout, "Stopped swarm (pid", pid, ").")
			return 0
		}
		time.Sleep(100 * time.Millisecond)
	}

	_ = syscall.Kill(pid, syscall.SIGKILL)
	fmt.Fprintln(env.Stdout, "Swarm did not exit after SIGTERM; force-stopped (pid", pid, ").")
	fmt.Fprintln(env.Stdout, "Warning: agent processes may have been orphaned if SIGKILL interrupted cleanup.")
	return 0
}
