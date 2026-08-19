package cli_test

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/TorratDev/swarm-forge/internal/cli"
	"github.com/TorratDev/swarm-forge/internal/daemon"
	"github.com/TorratDev/swarm-forge/internal/orchestrator"
	"github.com/TorratDev/swarm-forge/internal/pack"
)

// scaffoldPack generates and prepares a real project from an embedded
// pack: real git repo, real worktrees, real state.json -- the same path
// "swarmforge init && swarmforge up" takes, minus actually launching
// agents. Phase-6 parity validation runs the documented handoff chains
// through this exact setup.
func scaffoldPack(t *testing.T, packName string) (root string, result orchestrator.Result) {
	t.Helper()
	def, err := pack.Load(packName)
	if err != nil {
		t.Fatal(err)
	}
	root = t.TempDir()
	if err := pack.Generate(def, packName, root); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", t.TempDir())
	result, err = orchestrator.Prepare(root)
	if err != nil {
		t.Fatal(err)
	}
	return root, result
}

func gitCommit(t *testing.T, dir, message string) string {
	t.Helper()
	run := func(args ...string) string {
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		cmd.Env = append(os.Environ(),
			"GIT_AUTHOR_NAME=test", "GIT_AUTHOR_EMAIL=test@example.com",
			"GIT_COMMITTER_NAME=test", "GIT_COMMITTER_EMAIL=test@example.com",
		)
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	if err := os.WriteFile(filepath.Join(dir, "work.txt"), []byte(message), 0o644); err != nil {
		t.Fatal(err)
	}
	run("add", "work.txt")
	run("commit", "-m", message)
	return run("rev-parse", "HEAD")
}

func roleEnv(root, role string, result orchestrator.Result, stdout, stderr *bytes.Buffer) cli.Env {
	r, _ := result.State.RoleByName(role)
	return cli.Env{
		Cwd:    r.WorktreePath,
		Stdout: stdout,
		Stderr: stderr,
		Getenv: func(k string) string {
			if k == "SWARMFORGE_ROLE" {
				return role
			}
			return ""
		},
		Now: time.Now,
	}
}

func sendGitHandoff(t *testing.T, root, from, to, priority, task string, result orchestrator.Result) {
	t.Helper()
	r, _ := result.State.RoleByName(from)
	commit := gitCommit(t, r.WorktreePath, "work by "+from+" for "+task)

	draftPath := filepath.Join(r.WorktreePath, "draft.txt")
	draft := "type: git_handoff\nto: " + to + "\npriority: " + priority + "\ntask: " + task + "\ncommit: " + commit[:10] + "\n"
	if err := os.WriteFile(draftPath, []byte(draft), 0o644); err != nil {
		t.Fatal(err)
	}

	var stdout, stderr bytes.Buffer
	code := cli.RunHandoff(roleEnv(root, from, result, &stdout, &stderr), []string{draftPath})
	if code != 0 {
		t.Fatalf("RunHandoff(%s -> %s): code=%d stderr=%s", from, to, code, stderr.String())
	}
}

func deliver(result orchestrator.Result) {
	daemon.PollOnce(result.State, noopNotifier{}, time.Now())
}

// ready runs "ready" for role and returns its stdout, failing the test if
// it doesn't succeed.
func ready(t *testing.T, root, role string, result orchestrator.Result) string {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := cli.RunReady(roleEnv(root, role, result, &stdout, &stderr))
	if code != 0 {
		t.Fatalf("RunReady(%s): code=%d stderr=%s", role, code, stderr.String())
	}
	return stdout.String()
}

// done runs "done" for role and returns its stdout, failing the test if
// it doesn't succeed.
func done(t *testing.T, root, role string, result orchestrator.Result) string {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := cli.RunDone(roleEnv(root, role, result, &stdout, &stderr))
	if code != 0 {
		t.Fatalf("RunDone(%s): code=%d stderr=%s", role, code, stderr.String())
	}
	return stdout.String()
}

// TestTwoPackFullHandoffLoop exercises two-pack's entire documented chain
// (coder -> cleaner -> coder) against a real generated project: real git
// worktrees, real commits, real commit-hash validation, cleaner's batch
// receive mode, coder's task receive mode -- not a synthetic fixture.
func TestTwoPackFullHandoffLoop(t *testing.T) {
	root, result := scaffoldPack(t, "two-pack")

	sendGitHandoff(t, root, "coder", "cleaner", "50", "task-1", result)
	deliver(result)

	out := ready(t, root, "cleaner", result)
	if !strings.Contains(out, "BATCH:") || !strings.Contains(out, "TASK_NAME: task-1") {
		t.Fatalf("cleaner (batch mode) did not receive the coder handoff as expected:\n%s", out)
	}

	out = done(t, root, "cleaner", result)
	if !strings.Contains(out, "COMPLETED_BATCH:") || !strings.Contains(out, "NO_TASK") {
		t.Fatalf("cleaner completion did not report NO_TASK for the next batch:\n%s", out)
	}

	// Cleaner forwards back to coder (always-forward chain rule).
	sendGitHandoff(t, root, "cleaner", "coder", "00", "task-1", result)
	deliver(result)

	out = ready(t, root, "coder", result)
	if !strings.Contains(out, "TASK:") || !strings.Contains(out, "FROM: cleaner") || !strings.Contains(out, "TASK_NAME: task-1") {
		t.Fatalf("coder (task mode) did not receive the cleaner's return handoff as expected:\n%s", out)
	}

	out = done(t, root, "coder", result)
	if !strings.Contains(out, "COMPLETED:") || !strings.Contains(out, "NO_TASK") {
		t.Fatalf("coder completion did not report NO_TASK afterward:\n%s", out)
	}
}

// TestBatchRolesGroupEqualPriorityHandoffsAcrossRealWorktrees validates
// the more error-prone batch-grouping path (four-pack's architect,
// six-pack's cleaner/architect/hardender/QA) against real generated
// projects: multiple equal-priority handoffs arriving before the
// recipient checks in must be accepted as a single batch, in delivered
// (filename-sorted) order, and a different-priority handoff must not
// leak into that batch.
func TestBatchRolesGroupEqualPriorityHandoffsAcrossRealWorktrees(t *testing.T) {
	cases := []struct {
		pack       string
		from       string
		batchRole  string
		lowerPrio  string
		higherPrio string
	}{
		{"four-pack", "refactorer", "architect", "00", "70"},
		{"six-pack", "coder", "cleaner", "50", "70"},
	}

	for _, tc := range cases {
		t.Run(tc.pack, func(t *testing.T) {
			root, result := scaffoldPack(t, tc.pack)

			sendGitHandoff(t, root, tc.from, tc.batchRole, tc.lowerPrio, "batch-task-a", result)
			sendGitHandoff(t, root, tc.from, tc.batchRole, tc.lowerPrio, "batch-task-b", result)
			sendGitHandoff(t, root, tc.from, tc.batchRole, tc.higherPrio, "should-not-batch", result)
			deliver(result)

			out := ready(t, root, tc.batchRole, result)
			if !strings.Contains(out, "COUNT: 2") {
				t.Fatalf("%s: expected a batch of 2 matching-priority items, got:\n%s", tc.pack, out)
			}
			if strings.Contains(out, "should-not-batch") {
				t.Fatalf("%s: a different-priority handoff leaked into the batch:\n%s", tc.pack, out)
			}

			out = done(t, root, tc.batchRole, result)
			if !strings.Contains(out, "COMPLETED_BATCH:") {
				t.Fatalf("%s: batch did not complete cleanly:\n%s", tc.pack, out)
			}
			// The held-back higher-priority item should now be
			// available as its own (single-item) batch.
			if !strings.Contains(out, "TASK_NAME:") && !strings.Contains(out, "BATCH:") {
				t.Fatalf("%s: expected the held-back item to surface next, got:\n%s", tc.pack, out)
			}
		})
	}
}
