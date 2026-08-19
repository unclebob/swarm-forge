package ptyagent

import "syscall"

// processGone reports whether pid no longer exists, using the signal-0
// idiom (kill(pid, 0) fails with ESRCH once the process is reaped).
func processGone(pid int) bool {
	err := syscall.Kill(pid, 0)
	return err == syscall.ESRCH
}
