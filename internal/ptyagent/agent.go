// Package ptyagent spawns an agent CLI subprocess under a pseudo-terminal
// so its output can be captured and its input driven programmatically --
// the mechanism the TUI uses instead of shelling out to tmux/native
// terminal windows.
package ptyagent

import (
	"os"
	"os/exec"
	"syscall"
	"time"

	"github.com/creack/pty"
)

// Note: we deliberately do not set SysProcAttr.Setpgid ourselves.
// pty.StartWithSize already sets Setsid (to make the child a session
// leader with the PTY as its controlling terminal), which as a side
// effect makes the child its own process group leader (pgid == pid) --
// setpgid(2) itself would fail with EPERM once setsid(2) has already run,
// since a session leader can't be re-assigned to a different process
// group. Close() below relies on pgid == pid to signal the whole tree.

// Agent is one subprocess running under a PTY, in its own process group so
// the whole tree it spawns can be torn down in one signal (see Close).
type Agent struct {
	Cmd *exec.Cmd
	PTY *os.File
}

// Start launches name with args in dir (with the given environment, or
// the current process's environment if env is nil) under a new PTY sized
// cols x rows.
func Start(name string, args []string, dir string, env []string, cols, rows int) (*Agent, error) {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	if env != nil {
		cmd.Env = env
	}

	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: uint16(rows), Cols: uint16(cols)})
	if err != nil {
		return nil, err
	}
	return &Agent{Cmd: cmd, PTY: ptmx}, nil
}

// Resize propagates a terminal size change to the PTY (and, via SIGWINCH,
// to the child process).
func (a *Agent) Resize(cols, rows int) error {
	return pty.Setsize(a.PTY, &pty.Winsize{Rows: uint16(rows), Cols: uint16(cols)})
}

// Write sends bytes to the child's stdin (its PTY slave).
func (a *Agent) Write(p []byte) (int, error) {
	return a.PTY.Write(p)
}

// Wait blocks until the child process exits.
func (a *Agent) Wait() error {
	return a.Cmd.Wait()
}

// Close terminates the agent's entire process group (SIGTERM, escalating
// to SIGKILL after timeout if it doesn't exit) and closes the PTY. This is
// what lets a single Close call reap every subprocess an agent CLI itself
// spawned, matching what "tmux kill-session" did implicitly in the
// original tmux-based design.
func (a *Agent) Close(timeout time.Duration) error {
	if a.Cmd.Process != nil {
		pgid := a.Cmd.Process.Pid
		_ = syscall.Kill(-pgid, syscall.SIGTERM)

		done := make(chan struct{})
		go func() {
			_ = a.Cmd.Wait()
			close(done)
		}()
		select {
		case <-done:
		case <-time.After(timeout):
			_ = syscall.Kill(-pgid, syscall.SIGKILL)
			<-done
		}
	}
	return a.PTY.Close()
}
