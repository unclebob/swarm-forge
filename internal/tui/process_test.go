package tui

import "syscall"

func processStillRunning(pid int) bool {
	return syscall.Kill(pid, 0) == nil
}
