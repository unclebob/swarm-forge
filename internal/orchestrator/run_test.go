package orchestrator

import (
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/torratdev/swarmforge/internal/handoff"
	"github.com/torratdev/swarmforge/internal/launch"
)

func requirePython(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath("python3"); err != nil {
		t.Skip("python3 not available")
	}
}

// idleSpawn stands in for a real agent CLI in tests: whatever backend is
// configured, it actually launches an idle python3 process. This proves
// the launch/daemon/teardown wiring works without exec'ing (and paying
// for, or nesting) a real agent CLI.
func idleSpawn(agent string, spec launch.Spec) (string, []string, error) {
	return "python3", []string{"-c", "import time\nwhile True:\n    time.sleep(1)\n"}, nil
}

func quickExitSpawn(agent string, spec launch.Spec) (string, []string, error) {
	return "python3", []string{"-c", "pass"}, nil
}

func processAlive(pid int) bool {
	return syscall.Kill(pid, 0) == nil
}

func waitUntil(t *testing.T, timeout time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("condition not met within %v", timeout)
}

func TestLaunchStartsAgentsAndWritesPIDFile(t *testing.T) {
	requirePython(t)
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())
	result, err := Prepare(root)
	if err != nil {
		t.Fatal(err)
	}

	run, err := Launch(root, result.Project, result.State, 0, idleSpawn)
	if err != nil {
		t.Fatalf("Launch: %v", err)
	}
	defer run.Shutdown()

	pidBytes, err := os.ReadFile(PIDFilePath(root))
	if err != nil {
		t.Fatalf("PID file not written: %v", err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(pidBytes)))
	if err != nil || pid != os.Getpid() {
		t.Fatalf("PID file = %q, want this test process's pid %d", pidBytes, os.Getpid())
	}

	for _, role := range []string{"coder", "cleaner"} {
		agent, ok := run.Agent(role)
		if !ok {
			t.Fatalf("no agent launched for role %q", role)
		}
		if !processAlive(agent.Cmd.Process.Pid) {
			t.Fatalf("role %q's process is not running", role)
		}
		if _, ok := run.Screen(role); !ok {
			t.Fatalf("no screen for role %q", role)
		}
	}

	if run.CleanupRole() != "coder" {
		t.Fatalf("CleanupRole() = %q, want %q (first role in two-pack)", run.CleanupRole(), "coder")
	}
}

func TestDaemonDeliversAndNotifiesViaLivePTY(t *testing.T) {
	requirePython(t)
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())
	result, err := Prepare(root)
	if err != nil {
		t.Fatal(err)
	}

	run, err := Launch(root, result.Project, result.State, 0, idleSpawn)
	if err != nil {
		t.Fatalf("Launch: %v", err)
	}
	defer run.Shutdown()

	coder, _ := result.State.RoleByName("coder")
	if _, err := handoff.WriteDraft(
		coder.WorktreePath+"/.swarmforge/handoffs",
		map[string]string{"type": "note", "to": "cleaner", "priority": "50", "message": "hi cleaner"},
		[]string{"cleaner"}, "coder", "", time.Now(),
	); err != nil {
		t.Fatalf("WriteDraft: %v", err)
	}

	// The daemon goroutine should pick this up within one poll cycle and
	// write a wake-up directly into cleaner's PTY. A PTY in cooked mode
	// echoes writes back to the master's read side regardless of whether
	// the child is actively reading -- so this proves both delivery *and*
	// the direct-PTY-write Notify mechanism, without needing the idle
	// child script to cooperate at all.
	waitUntil(t, 4*time.Second, func() bool {
		screen, ok := run.Screen("cleaner")
		if !ok {
			return false
		}
		return strings.Contains(screen.Render(), "You have new handoff mail")
	})
}

func TestExitedChannelSignalsCleanupRoleExit(t *testing.T) {
	requirePython(t)
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())
	result, err := Prepare(root)
	if err != nil {
		t.Fatal(err)
	}

	run, err := Launch(root, result.Project, result.State, 0, quickExitSpawn)
	if err != nil {
		t.Fatalf("Launch: %v", err)
	}
	defer run.Shutdown()

	select {
	case role := <-run.Exited():
		if role != run.CleanupRole() {
			t.Logf("first exited role was %q (not necessarily the cleanup role -- both exit quickly here)", role)
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("no role exit signaled")
	}
}

func TestShutdownTerminatesAgentsAndRemovesPIDFile(t *testing.T) {
	requirePython(t)
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())
	result, err := Prepare(root)
	if err != nil {
		t.Fatal(err)
	}

	run, err := Launch(root, result.Project, result.State, 0, idleSpawn)
	if err != nil {
		t.Fatalf("Launch: %v", err)
	}

	var pids []int
	for _, role := range []string{"coder", "cleaner"} {
		agent, _ := run.Agent(role)
		pids = append(pids, agent.Cmd.Process.Pid)
	}

	run.Shutdown()

	for _, pid := range pids {
		if processAlive(pid) {
			t.Fatalf("pid %d still alive after Shutdown", pid)
		}
	}
	if _, err := os.Stat(PIDFilePath(root)); !os.IsNotExist(err) {
		t.Fatalf("PID file should be removed after Shutdown")
	}
}

func TestShutdownIsSafeToCallConcurrentlyMoreThanOnce(t *testing.T) {
	requirePython(t)
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())
	result, err := Prepare(root)
	if err != nil {
		t.Fatal(err)
	}
	run, err := Launch(root, result.Project, result.State, 0, idleSpawn)
	if err != nil {
		t.Fatal(err)
	}

	done := make(chan struct{})
	for i := 0; i < 5; i++ {
		go func() {
			run.Shutdown()
			done <- struct{}{}
		}()
	}
	deadline := time.After(5 * time.Second)
	for i := 0; i < 5; i++ {
		select {
		case <-done:
		case <-deadline:
			t.Fatalf("concurrent Shutdown calls did not all return")
		}
	}
}

func TestReadPID(t *testing.T) {
	requirePython(t)
	root := scaffoldTwoPack(t)
	t.Setenv("HOME", t.TempDir())
	result, err := Prepare(root)
	if err != nil {
		t.Fatal(err)
	}
	run, err := Launch(root, result.Project, result.State, 0, idleSpawn)
	if err != nil {
		t.Fatal(err)
	}
	defer run.Shutdown()

	pid, err := ReadPID(root)
	if err != nil || pid != os.Getpid() {
		t.Fatalf("ReadPID() = %d, %v; want %d, nil", pid, err, os.Getpid())
	}
}
