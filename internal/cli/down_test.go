package cli_test

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/torratdev/swarmforge/internal/cli"
)

func requirePython(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath("python3"); err != nil {
		t.Skip("python3 not available")
	}
}

func writePIDFile(t *testing.T, root string, pid int) {
	t.Helper()
	dir := filepath.Join(root, ".swarmforge")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "swarmforge.pid"), []byte(strconv.Itoa(pid)+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestRunDownStopsRunningProcess(t *testing.T) {
	requirePython(t)
	root := t.TempDir()

	cmd := exec.Command("python3", "-c", "import time; time.sleep(30)")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	// Reap the child once it's killed -- otherwise it lingers as a zombie,
	// and kill(pid, 0) keeps succeeding against a zombie's still-allocated
	// PID, which would make RunDown wrongly conclude the process is still
	// alive and escalate to SIGKILL.
	go cmd.Wait()
	writePIDFile(t, root, cmd.Process.Pid)

	var stdout, stderr bytes.Buffer
	env := cli.Env{Cwd: root, Stdout: &stdout, Stderr: &stderr, Getenv: func(string) string { return "" }, Now: time.Now}
	code := cli.RunDown(env, root)
	if code != 0 {
		t.Fatalf("code=%d stderr=%s", code, stderr.String())
	}
	if !strings.Contains(stdout.String(), "Stopped swarm") {
		t.Fatalf("unexpected output: %s", stdout.String())
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if syscall.Kill(cmd.Process.Pid, 0) != nil {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("process %d still alive after RunDown", cmd.Process.Pid)
}

func TestRunDownNoRunningSwarm(t *testing.T) {
	root := t.TempDir()
	var stdout, stderr bytes.Buffer
	env := cli.Env{Cwd: root, Stdout: &stdout, Stderr: &stderr, Getenv: func(string) string { return "" }, Now: time.Now}
	code := cli.RunDown(env, root)
	if code != 1 || !strings.Contains(stderr.String(), "no running swarm found") {
		t.Fatalf("code=%d stderr=%s", code, stderr.String())
	}
}

func TestRunDownStalePIDFile(t *testing.T) {
	root := t.TempDir()
	// A PID astronomically unlikely to be a live process.
	writePIDFile(t, root, 1<<30)

	var stdout, stderr bytes.Buffer
	env := cli.Env{Cwd: root, Stdout: &stdout, Stderr: &stderr, Getenv: func(string) string { return "" }, Now: time.Now}
	code := cli.RunDown(env, root)
	// Signaling a nonexistent PID fails at the SIGTERM step -- RunDown
	// should report that cleanly, not hang or panic.
	if code != 1 {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}
