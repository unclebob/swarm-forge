package ptyagent

import (
	"bufio"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func requirePython(t *testing.T) string {
	t.Helper()
	path, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 not available")
	}
	return path
}

func TestStartWriteAndExit(t *testing.T) {
	python := requirePython(t)
	agent, err := Start(python, []string{"-c", "print('got: ' + input())"}, t.TempDir(), nil, 80, 24)
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer agent.Close(2 * time.Second)

	if _, err := agent.Write([]byte("hello\r\n")); err != nil {
		t.Fatalf("Write: %v", err)
	}

	br := bufio.NewReader(agent.PTY)
	deadline := time.Now().Add(3 * time.Second)
	var seen strings.Builder
	for time.Now().Before(deadline) {
		line, err := br.ReadString('\n')
		seen.WriteString(line)
		if strings.Contains(seen.String(), "got: hello") {
			return
		}
		if err != nil {
			break
		}
	}
	t.Fatalf("did not see expected echo; got: %q", seen.String())
}

func TestCloseTerminatesRunningProcess(t *testing.T) {
	python := requirePython(t)
	agent, err := Start(python, []string{"-c", "import time; time.sleep(30)"}, t.TempDir(), nil, 80, 24)
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	pid := agent.Cmd.Process.Pid

	if err := agent.Close(2 * time.Second); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if !processGone(pid) {
		t.Fatalf("process %d still running after Close", pid)
	}
}

func TestCloseEscalatesToSigkillWhenTermIgnored(t *testing.T) {
	python := requirePython(t)
	// Ignore SIGTERM so Close must fall back to SIGKILL within its timeout.
	script := "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)"
	agent, err := Start(python, []string{"-c", script}, t.TempDir(), nil, 80, 24)
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	pid := agent.Cmd.Process.Pid

	// Give the child a moment to install the signal handler before we
	// race it with Close.
	time.Sleep(200 * time.Millisecond)

	start := time.Now()
	if err := agent.Close(500 * time.Millisecond); err != nil {
		t.Fatalf("Close: %v", err)
	}
	elapsed := time.Since(start)
	if elapsed > 3*time.Second {
		t.Fatalf("Close took too long to escalate to SIGKILL: %v", elapsed)
	}
	if !processGone(pid) {
		t.Fatalf("process %d still running after Close escalated to SIGKILL", pid)
	}
}
