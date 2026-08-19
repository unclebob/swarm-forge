package handoff

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func writeHandoffFile(t *testing.T, dir, name, headers, body string) string {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, name)
	content := headers + "\n\n" + body
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

var testNow = time.Date(2026, 6, 15, 14, 5, 31, 0, time.UTC)

func TestReadyForNextTaskNoTask(t *testing.T) {
	inbox := filepath.Join(t.TempDir(), "inbox")
	var stdout, stderr bytes.Buffer
	code := ReadyForNextTask(inbox, testNow, &stdout, &stderr)
	if code != 0 || strings.TrimSpace(stdout.String()) != "NO_TASK" {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
}

func TestReadyForNextTaskAcceptsOldestByFilename(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	newDir := filepath.Join(inbox, "new")
	writeHandoffFile(t, newDir, "50_20260615T140600Z_000002_from_a_to_b.handoff", "from: a\ntype: note\npriority: 50", "second")
	writeHandoffFile(t, newDir, "50_20260615T140500Z_000001_from_a_to_b.handoff", "from: a\ntype: note\npriority: 50", "first")

	var stdout, stderr bytes.Buffer
	code := ReadyForNextTask(inbox, testNow, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	if !strings.Contains(stdout.String(), "50_20260615T140500Z_000001") {
		t.Fatalf("expected the earlier file to be accepted first, got:\n%s", stdout.String())
	}
	if !strings.Contains(stdout.String(), "PAYLOAD:\nfirst") {
		t.Fatalf("payload not printed correctly, got:\n%s", stdout.String())
	}
	inProcess := ListHandoffFiles(filepath.Join(inbox, "in_process"))
	if len(inProcess) != 1 {
		t.Fatalf("expected exactly one in-process file, got %v", inProcess)
	}
	if dq, ok := HeaderField(inProcess[0], "dequeued_at"); !ok || dq == "" {
		t.Fatalf("dequeued_at not stamped")
	}
}

func TestReadyForNextTaskIdempotentWhenAlreadyInProcess(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	writeHandoffFile(t, filepath.Join(inbox, "in_process"), "50_20260615T140500Z_000001_from_a_to_b.handoff", "from: a\ntype: note\npriority: 50", "x")

	var stdout, stderr bytes.Buffer
	code := ReadyForNextTask(inbox, testNow, &stdout, &stderr)
	if code != 0 || !strings.Contains(stdout.String(), "TASK:") {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
}

func TestReadyForNextTaskAmbiguousMultipleInProcess(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	writeHandoffFile(t, filepath.Join(inbox, "in_process"), "50_a.handoff", "from: a\ntype: note\npriority: 50", "x")
	writeHandoffFile(t, filepath.Join(inbox, "in_process"), "50_b.handoff", "from: a\ntype: note\npriority: 50", "x")

	var stdout, stderr bytes.Buffer
	code := ReadyForNextTask(inbox, testNow, &stdout, &stderr)
	if code != 2 || !strings.Contains(stderr.String(), "AMBIGUOUS_TASK_STATE") {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
}

func TestReadyForNextTaskRefusesWhenBatchInProcess(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	if err := os.MkdirAll(filepath.Join(inbox, "in_process", "batch_x"), 0o755); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	code := ReadyForNextTask(inbox, testNow, &stdout, &stderr)
	if code != 2 || !strings.Contains(stderr.String(), "TASK_IN_PROCESS_IS_BATCH") {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
}

func TestDoneWithCurrentTaskCompletesAndPullsNext(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	writeHandoffFile(t, filepath.Join(inbox, "in_process"), "50_20260615T140500Z_000001_from_a_to_b.handoff", "from: a\ntype: note\npriority: 50", "current")
	writeHandoffFile(t, filepath.Join(inbox, "new"), "50_20260615T140600Z_000002_from_a_to_b.handoff", "from: a\ntype: note\npriority: 50", "next")

	var stdout, stderr bytes.Buffer
	code := DoneWithCurrentTask(inbox, testNow, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	out := stdout.String()
	if !strings.Contains(out, "COMPLETED:") || !strings.Contains(out, "TASK:") || !strings.Contains(out, "PAYLOAD:\nnext") {
		t.Fatalf("unexpected output:\n%s", out)
	}
	completed := ListHandoffFiles(filepath.Join(inbox, "completed"))
	if len(completed) != 1 {
		t.Fatalf("expected one completed file, got %v", completed)
	}
	if ca, ok := HeaderField(completed[0], "completed_at"); !ok || ca == "" {
		t.Fatalf("completed_at not stamped")
	}
}

func TestDoneWithCurrentTaskNoCurrentTask(t *testing.T) {
	inbox := filepath.Join(t.TempDir(), "inbox")
	var stdout, stderr bytes.Buffer
	code := DoneWithCurrentTask(inbox, testNow, &stdout, &stderr)
	if code != 1 || !strings.Contains(stderr.String(), "NO_CURRENT_TASK") {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
}

func TestReadyForNextBatchGroupsByMatchingPriorityOnly(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	newDir := filepath.Join(inbox, "new")
	writeHandoffFile(t, newDir, "50_20260615T140500Z_000001_from_a_to_b.handoff", "from: a\ntype: note\npriority: 50", "p50-first")
	writeHandoffFile(t, newDir, "50_20260615T140600Z_000002_from_a_to_b.handoff", "from: a\ntype: note\npriority: 50", "p50-second")
	writeHandoffFile(t, newDir, "70_20260615T140700Z_000003_from_a_to_b.handoff", "from: a\ntype: note\npriority: 70", "p70-should-not-batch")

	var stdout, stderr bytes.Buffer
	code := ReadyForNextBatch(inbox, testNow, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	out := stdout.String()
	if !strings.Contains(out, "COUNT: 2") {
		t.Fatalf("expected batch of 2 (matching priority 50 only), got:\n%s", out)
	}
	if strings.Contains(out, "p70-should-not-batch") {
		t.Fatalf("priority-70 item leaked into the batch:\n%s", out)
	}
	// the priority-70 item must remain queued in new/
	remaining := ListHandoffFiles(newDir)
	if len(remaining) != 1 {
		t.Fatalf("expected the non-matching-priority file to remain in new/, got %v", remaining)
	}
}

func TestDoneWithCurrentBatchCompletesWholeBatch(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	batchDir := filepath.Join(inbox, "in_process", "batch_20260615T140000Z_000001")
	writeHandoffFile(t, batchDir, "50_a.handoff", "from: a\ntype: note\npriority: 50", "x")
	writeHandoffFile(t, batchDir, "50_b.handoff", "from: a\ntype: note\npriority: 50", "y")

	var stdout, stderr bytes.Buffer
	code := DoneWithCurrentBatch(inbox, testNow, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	out := stdout.String()
	if strings.Count(out, "COMPLETED:") != 2 || !strings.Contains(out, "COMPLETED_BATCH:") || !strings.Contains(out, "NO_TASK") {
		t.Fatalf("unexpected output:\n%s", out)
	}
	if _, err := os.Stat(batchDir); !os.IsNotExist(err) {
		t.Fatalf("source batch dir should have been removed")
	}
}

func TestReadyForNextDispatchesByReceiveMode(t *testing.T) {
	root := t.TempDir()
	inbox := filepath.Join(root, "inbox")
	writeHandoffFile(t, filepath.Join(inbox, "new"), "50_a.handoff", "from: a\ntype: note\npriority: 50", "x")

	var stdout, stderr bytes.Buffer
	if code := ReadyForNext(inbox, "batch", testNow, &stdout, &stderr); code != 0 || !strings.Contains(stdout.String(), "BATCH:") {
		t.Fatalf("batch dispatch failed: code=%d out=%q err=%q", code, stdout.String(), stderr.String())
	}
}

func TestReadyForNextInvalidReceiveMode(t *testing.T) {
	inbox := filepath.Join(t.TempDir(), "inbox")
	var stdout, stderr bytes.Buffer
	code := ReadyForNext(inbox, "bogus", testNow, &stdout, &stderr)
	if code != 2 || !strings.Contains(stderr.String(), "INVALID_RECEIVE_MODE") {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
}

func TestSetHeaderInsertsAndReplaces(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "x.handoff")
	// Real handoff files always end with a trailing newline after the
	// body (see WriteDraft); SetHeader must preserve that shape exactly.
	if err := os.WriteFile(path, []byte("from: a\ntype: note\n\nbody\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := SetHeader(path, "dequeued_at", "2026-06-15T14:05:31Z"); err != nil {
		t.Fatal(err)
	}
	v, ok := HeaderField(path, "dequeued_at")
	if !ok || v != "2026-06-15T14:05:31Z" {
		t.Fatalf("header not inserted: ok=%v v=%q", ok, v)
	}
	if err := SetHeader(path, "dequeued_at", "later"); err != nil {
		t.Fatal(err)
	}
	v, _ = HeaderField(path, "dequeued_at")
	if v != "later" {
		t.Fatalf("header not replaced: %q", v)
	}
	body := BodyOf(path)
	if body != "body\n" {
		t.Fatalf("body corrupted by SetHeader: %q", body)
	}
}
