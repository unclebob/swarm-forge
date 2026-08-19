package cli_test

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/TorratDev/swarm-forge/internal/cli"
	"github.com/TorratDev/swarm-forge/internal/daemon"
	"github.com/TorratDev/swarm-forge/internal/state"
)

type noopNotifier struct{}

func (noopNotifier) Notify(string) error { return nil }

// TestHandoffReadyDoneEndToEnd exercises the full agent-facing pipeline
// end to end: coder queues a handoff, the daemon delivers it into
// cleaner's inbox, cleaner accepts it via "ready" and completes it via
// "done" -- against a fixture project directory, no PTY/TUI/git involved.
func TestHandoffReadyDoneEndToEnd(t *testing.T) {
	root := t.TempDir()
	coderDir := filepath.Join(root, "coder")
	cleanerDir := filepath.Join(root, "cleaner")
	for _, d := range []string{coderDir, cleanerDir} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
	}

	st := state.State{Roles: []state.Role{
		{Name: "coder", WorktreePath: coderDir, ReceiveMode: "task"},
		{Name: "cleaner", WorktreePath: cleanerDir, ReceiveMode: "task"},
	}}
	// Each worktree carries its own copy of state.json so project-root
	// discovery succeeds trivially from either directory.
	if err := state.Save(coderDir, st); err != nil {
		t.Fatal(err)
	}
	if err := state.Save(cleanerDir, st); err != nil {
		t.Fatal(err)
	}

	now := time.Date(2026, 6, 15, 14, 5, 31, 0, time.UTC)
	envFor := func(role, cwd string, stdout, stderr *bytes.Buffer) cli.Env {
		return cli.Env{
			Cwd:    cwd,
			Stdout: stdout,
			Stderr: stderr,
			Getenv: func(k string) string {
				if k == "SWARMFORGE_ROLE" {
					return role
				}
				return ""
			},
			Now: func() time.Time { return now },
		}
	}

	draftPath := filepath.Join(coderDir, "draft.txt")
	if err := os.WriteFile(draftPath, []byte("type: note\nto: cleaner\npriority: 50\nmessage: hello cleaner\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	var handoffOut, handoffErr bytes.Buffer
	code := cli.RunHandoff(envFor("coder", coderDir, &handoffOut, &handoffErr), []string{draftPath})
	if code != 0 {
		t.Fatalf("RunHandoff failed: code=%d stderr=%s", code, handoffErr.String())
	}
	if !strings.Contains(handoffOut.String(), "HANDOFF QUEUED:") {
		t.Fatalf("unexpected handoff output: %s", handoffOut.String())
	}
	if _, err := os.Stat(draftPath); !os.IsNotExist(err) {
		t.Fatalf("draft file should have been consumed")
	}

	events := daemon.PollOnce(st, noopNotifier{}, now)
	for _, e := range events {
		if e.Kind != "delivered" {
			t.Fatalf("unexpected daemon event: %+v", e)
		}
	}

	var readyOut, readyErr bytes.Buffer
	code = cli.RunReady(envFor("cleaner", cleanerDir, &readyOut, &readyErr))
	if code != 0 {
		t.Fatalf("RunReady failed: code=%d stderr=%s", code, readyErr.String())
	}
	if !strings.Contains(readyOut.String(), "TASK:") || !strings.Contains(readyOut.String(), "hello cleaner") {
		t.Fatalf("unexpected ready output: %s", readyOut.String())
	}

	var doneOut, doneErr bytes.Buffer
	code = cli.RunDone(envFor("cleaner", cleanerDir, &doneOut, &doneErr))
	if code != 0 {
		t.Fatalf("RunDone failed: code=%d stderr=%s", code, doneErr.String())
	}
	if !strings.Contains(doneOut.String(), "COMPLETED:") || !strings.Contains(doneOut.String(), "NO_TASK") {
		t.Fatalf("unexpected done output: %s", doneOut.String())
	}
}

func TestRunHandoffRejectsInvalidDraft(t *testing.T) {
	root := t.TempDir()
	st := state.State{Roles: []state.Role{{Name: "coder", WorktreePath: root, ReceiveMode: "task"}}}
	if err := state.Save(root, st); err != nil {
		t.Fatal(err)
	}
	draftPath := filepath.Join(root, "draft.txt")
	if err := os.WriteFile(draftPath, []byte("type: note\nto: coder\npriority: nope\nmessage: hi\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	env := cli.Env{
		Cwd: root, Stdout: &stdout, Stderr: &stderr,
		Getenv: func(k string) string {
			if k == "SWARMFORGE_ROLE" {
				return "coder"
			}
			return ""
		},
		Now: time.Now,
	}
	code := cli.RunHandoff(env, []string{draftPath})
	if code != 2 || !strings.Contains(stderr.String(), "HANDOFF INVALID") {
		t.Fatalf("code=%d stderr=%s", code, stderr.String())
	}
	if _, err := os.Stat(draftPath); err != nil {
		t.Fatalf("invalid draft should be left in place for the operator to fix: %v", err)
	}
}

func TestRunHandoffRequiresRole(t *testing.T) {
	var stdout, stderr bytes.Buffer
	env := cli.Env{Cwd: t.TempDir(), Stdout: &stdout, Stderr: &stderr, Getenv: func(string) string { return "" }, Now: time.Now}
	code := cli.RunHandoff(env, []string{"missing.txt"})
	if code != 1 || !strings.Contains(stderr.String(), "SWARMFORGE_ROLE") {
		t.Fatalf("code=%d stderr=%s", code, stderr.String())
	}
}
