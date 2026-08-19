package tui

import (
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"

	"github.com/torratdev/swarmforge/internal/launch"
	"github.com/torratdev/swarmforge/internal/orchestrator"
	"github.com/torratdev/swarmforge/internal/pack"
)

func requirePython(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath("python3"); err != nil {
		t.Skip("python3 not available")
	}
}

func idleSpawn(agent string, spec launch.Spec) (string, []string, error) {
	return "python3", []string{"-c", "import time\nwhile True:\n    time.sleep(1)\n"}, nil
}

// launchTestSwarm scaffolds a two-pack project, prepares it, and launches
// it with idle python processes standing in for real agent CLIs -- the
// same pattern internal/orchestrator's own tests use, so the TUI's key
// handling and rendering can be exercised against a real Run (real PTYs,
// real screens) without nesting or paying for a real agent CLI.
func launchTestSwarm(t *testing.T) (*orchestrator.Run, []string) {
	t.Helper()
	requirePython(t)

	def, err := pack.Load("two-pack")
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	if err := pack.Generate(def, "two-pack", root); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", t.TempDir())
	result, err := orchestrator.Prepare(root)
	if err != nil {
		t.Fatal(err)
	}
	run, err := orchestrator.Launch(root, result.Project, result.State, 0, idleSpawn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(run.Shutdown)

	roles := make([]string, len(result.Project.Roles))
	for i, r := range result.Project.Roles {
		roles[i] = r.Name
	}
	return run, roles
}

func TestLeaderKeyEntersAndExitsControlMode(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	updated, _ := m.Update(tea.KeyMsg{Type: LeaderKey})
	m = updated.(Model)
	if !m.LeaderMode() {
		t.Fatalf("expected leader mode after Ctrl-A")
	}

	// Any key after the leader key exits leader mode, whether or not it
	// maps to a recognized command.
	updated, _ = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("x")})
	m = updated.(Model)
	if m.LeaderMode() {
		t.Fatalf("expected leader mode to clear after the next keystroke")
	}
}

func TestLeaderDigitSwitchesFocus(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)
	if m.Focus() != 0 {
		t.Fatalf("expected initial focus 0, got %d", m.Focus())
	}

	updated, _ := m.Update(tea.KeyMsg{Type: LeaderKey})
	m = updated.(Model)
	updated, _ = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("2")})
	m = updated.(Model)

	if m.Focus() != 1 {
		t.Fatalf("expected focus 1 after leader+2, got %d", m.Focus())
	}
}

func TestLeaderDigitOutOfRangeIsIgnored(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	updated, _ := m.Update(tea.KeyMsg{Type: LeaderKey})
	m = updated.(Model)
	updated, _ = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("9")})
	m = updated.(Model)

	if m.Focus() != 0 {
		t.Fatalf("out-of-range pane number should be ignored, focus = %d", m.Focus())
	}
}

func TestNonLeaderKeysForwardToFocusedPane(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	m2, _ := m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("z")})
	m = m2.(Model)

	screen, ok := run.Screen(roles[0])
	if !ok {
		t.Fatal("no screen for focused role")
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(screen.Render(), "z") {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("forwarded key 'z' was never echoed into the focused pane's screen")
}

func TestCtrlCIsForwardedNotIntercepted(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	updated, cmd := m.Update(tea.KeyMsg{Type: tea.KeyCtrlC})
	m = updated.(Model)
	if m.Quitting() {
		t.Fatalf("Ctrl-C must not be intercepted as a quit -- only leader+q quits")
	}
	if cmd != nil {
		// A non-nil cmd here would typically indicate a tea.Quit was
		// queued; forwarding a keystroke to a PTY doesn't need one.
		t.Fatalf("expected no command from a forwarded Ctrl-C, got one")
	}
}

func TestLeaderQQuitsAndShutsDownSwarm(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	var pid int
	if agent, ok := run.Agent(roles[0]); ok {
		pid = agent.Cmd.Process.Pid
	}

	updated, _ := m.Update(tea.KeyMsg{Type: LeaderKey})
	m = updated.(Model)
	updated, cmd := m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("q")})
	m = updated.(Model)

	if !m.Quitting() {
		t.Fatalf("expected leader+q to set quitting")
	}
	if cmd == nil {
		t.Fatalf("expected leader+q to return a tea.Quit command")
	}
	if _, err := os.Stat(orchestrator.PIDFilePath(run.ProjectRoot)); !os.IsNotExist(err) {
		t.Fatalf("expected Shutdown (removing the PID file) to have run")
	}
	if pid != 0 && processStillRunning(pid) {
		t.Fatalf("agent process %d still running after leader+q", pid)
	}
}

func TestWindowSizeResizesAllPanes(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	updated, _ := m.Update(tea.WindowSizeMsg{Width: 120, Height: 40})
	m = updated.(Model)

	for _, role := range roles {
		screen, ok := run.Screen(role)
		if !ok {
			t.Fatalf("no screen for role %q", role)
		}
		cols, rows := screen.Size()
		if cols != 120 || rows != 39 {
			t.Fatalf("role %q screen size = %dx%d, want 120x39 (height minus the status bar)", role, cols, rows)
		}
	}
}

func TestExitedRoleThatIsNotCleanupRoleDoesNotQuit(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	nonCleanup := roles[len(roles)-1]
	if nonCleanup == run.CleanupRole() {
		t.Skip("two-pack's last role happens to be the cleanup role; nothing to test here")
	}

	updated, cmd := m.Update(exitedMsg(nonCleanup))
	m = updated.(Model)
	if m.Quitting() {
		t.Fatalf("a non-cleanup role exiting should not quit the whole swarm")
	}
	if cmd == nil {
		t.Fatalf("expected Update to keep listening for further exits")
	}
}

func TestCleanupRoleExitTriggersQuitAndShutdown(t *testing.T) {
	run, roles := launchTestSwarm(t)
	m := New(run, roles)

	updated, cmd := m.Update(exitedMsg(run.CleanupRole()))
	m = updated.(Model)
	if !m.Quitting() {
		t.Fatalf("cleanup role exiting should quit the whole swarm")
	}
	if cmd == nil {
		t.Fatalf("expected a tea.Quit command")
	}
	if _, err := os.Stat(orchestrator.PIDFilePath(run.ProjectRoot)); !os.IsNotExist(err) {
		t.Fatalf("expected Shutdown to have run (PID file removed)")
	}
}
