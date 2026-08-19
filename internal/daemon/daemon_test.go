package daemon

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/torratdev/swarmforge/internal/handoff"
	"github.com/torratdev/swarmforge/internal/state"
)

type fakeNotifier struct {
	mu      sync.Mutex
	calls   []string
	failFor map[string]bool
}

func (f *fakeNotifier) Notify(role string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, role)
	if f.failFor[role] {
		return os.ErrPermission
	}
	return nil
}

func setupRoles(t *testing.T, names ...string) (state.State, string) {
	t.Helper()
	root := t.TempDir()
	var st state.State
	for _, n := range names {
		wt := filepath.Join(root, n)
		st.Roles = append(st.Roles, state.Role{Name: n, WorktreePath: wt, ReceiveMode: "task"})
	}
	return st, root
}

func writeOutbox(t *testing.T, worktree, filename, headers, body string) string {
	t.Helper()
	dir := filepath.Join(worktree, ".swarmforge", "handoffs", "outbox")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, filename)
	if err := os.WriteFile(path, []byte(headers+"\n\n"+body), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

var testNow = time.Date(2026, 6, 15, 14, 5, 31, 0, time.UTC)

func TestPollOnceDeliversToRecipientInbox(t *testing.T) {
	st, _ := setupRoles(t, "coder", "cleaner")
	coderWt := st.Roles[0].WorktreePath
	writeOutbox(t, coderWt, "50_20260615T140531Z_000001_from_coder_to_cleaner.handoff",
		"id: x\nfrom: coder\nto: cleaner\npriority: 50\ntype: note\nmessage: hi\ncreated_at: 2026-06-15T14:00:00Z",
		"body")

	notifier := &fakeNotifier{}
	events := PollOnce(st, notifier, testNow)

	var delivered bool
	for _, e := range events {
		if e.Kind == "delivered" {
			delivered = true
		}
		if e.Kind == "error" || e.Kind == "failed" {
			t.Fatalf("unexpected event: %+v", e)
		}
	}
	if !delivered {
		t.Fatalf("expected a delivered event, got: %+v", events)
	}

	target := filepath.Join(st.Roles[1].WorktreePath, ".swarmforge", "handoffs", "inbox", "new", "50_20260615T140531Z_000001_from_coder_to_cleaner.handoff")
	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("recipient inbox copy missing: %v", err)
	}
	msg := handoff.Parse(string(content))
	if msg.Headers["recipient"] != "cleaner" || msg.Headers["enqueued_at"] == "" {
		t.Fatalf("delivery headers not stamped: %+v", msg.Headers)
	}

	sentPath := filepath.Join(coderWt, ".swarmforge", "handoffs", "sent", "50_20260615T140531Z_000001_from_coder_to_cleaner.handoff")
	if _, err := os.Stat(sentPath); err != nil {
		t.Fatalf("source file was not moved to sent/: %v", err)
	}

	if len(notifier.calls) != 1 || notifier.calls[0] != "cleaner" {
		t.Fatalf("expected exactly one notify(cleaner) call, got %v", notifier.calls)
	}
}

func TestPollOnceRedeliveryIsIdempotent(t *testing.T) {
	st, _ := setupRoles(t, "coder", "cleaner")
	coderWt := st.Roles[0].WorktreePath
	filename := "50_20260615T140531Z_000001_from_coder_to_cleaner.handoff"
	writeOutbox(t, coderWt, filename,
		"id: x\nfrom: coder\nto: cleaner\npriority: 50\ntype: note\nmessage: hi\ncreated_at: 2026-06-15T14:00:00Z",
		"body")

	notifier := &fakeNotifier{}
	PollOnce(st, notifier, testNow)

	target := filepath.Join(st.Roles[1].WorktreePath, ".swarmforge", "handoffs", "inbox", "new", filename)
	firstStat, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}

	// Simulate a second poll seeing a re-delivered outbox file (e.g. after
	// an interrupted first attempt): the recipient copy must not be
	// overwritten, but a wake-up should still be (re-)sent.
	writeOutbox(t, coderWt, filename,
		"id: x\nfrom: coder\nto: cleaner\npriority: 50\ntype: note\nmessage: hi\ncreated_at: 2026-06-15T14:00:00Z",
		"body")
	PollOnce(st, notifier, testNow.Add(time.Second))

	secondStat, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if !firstStat.ModTime().Equal(secondStat.ModTime()) {
		t.Fatalf("recipient inbox copy was overwritten on redelivery")
	}
	if len(notifier.calls) != 2 {
		t.Fatalf("expected a wake-up on both delivery attempts, got %v", notifier.calls)
	}
}

func TestPollOnceUnknownRecipientFailsWholeFile(t *testing.T) {
	st, _ := setupRoles(t, "coder")
	coderWt := st.Roles[0].WorktreePath
	filename := "50_20260615T140531Z_000001_from_coder_to_ghost.handoff"
	writeOutbox(t, coderWt, filename,
		"id: x\nfrom: coder\nto: ghost\npriority: 50\ntype: note\nmessage: hi\ncreated_at: 2026-06-15T14:00:00Z",
		"body")

	events := PollOnce(st, &fakeNotifier{}, testNow)
	var failed bool
	for _, e := range events {
		if e.Kind == "failed" {
			failed = true
		}
	}
	if !failed {
		t.Fatalf("expected a failed event for an unknown recipient, got: %+v", events)
	}

	failedPath := filepath.Join(coderWt, ".swarmforge", "handoffs", "failed", filename)
	if _, err := os.Stat(failedPath); err != nil {
		t.Fatalf("file not moved to failed/: %v", err)
	}
	errPath := filepath.Join(coderWt, ".swarmforge", "handoffs", "outbox", filename+".error")
	if _, err := os.Stat(errPath); err != nil {
		t.Fatalf(".error sidecar not written: %v", err)
	}
}

func TestPollOnceMissingToHeaderFails(t *testing.T) {
	st, _ := setupRoles(t, "coder")
	coderWt := st.Roles[0].WorktreePath
	filename := "50_20260615T140531Z_000001_from_coder_to_.handoff"
	writeOutbox(t, coderWt, filename,
		"id: x\nfrom: coder\npriority: 50\ntype: note\nmessage: hi",
		"body")

	events := PollOnce(st, &fakeNotifier{}, testNow)
	if len(events) != 1 || events[0].Kind != "failed" {
		t.Fatalf("expected a single failed event, got: %+v", events)
	}
}

func TestPollOnceOneRecipientErrorDoesNotCorruptOthers(t *testing.T) {
	st, _ := setupRoles(t, "coder", "cleaner")
	coderWt := st.Roles[0].WorktreePath
	filename := "50_20260615T140531Z_000001_from_coder_to_cleaner.handoff"
	writeOutbox(t, coderWt, filename,
		"id: x\nfrom: coder\nto: cleaner\npriority: 50\ntype: note\nmessage: hi\ncreated_at: 2026-06-15T14:00:00Z",
		"body")

	notifier := &fakeNotifier{failFor: map[string]bool{"cleaner": true}}
	events := PollOnce(st, notifier, testNow)

	// Delivery (the inbox copy) must still succeed even though the wake-up
	// notification failed -- notify failure is logged, not fatal.
	target := filepath.Join(st.Roles[1].WorktreePath, ".swarmforge", "handoffs", "inbox", "new", filename)
	if _, err := os.Stat(target); err != nil {
		t.Fatalf("inbox copy should exist despite notify failure: %v", err)
	}
	var sawError bool
	for _, e := range events {
		if e.Kind == "error" {
			sawError = true
		}
	}
	if !sawError {
		t.Fatalf("expected an error event recording the notify failure, got: %+v", events)
	}
}
