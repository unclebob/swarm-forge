// Package daemon implements the handoff delivery poll loop: scanning each
// role's outbox, copying complete handoffs into every recipient's inbox,
// and waking recipients up. In the full TUI this runs as a goroutine
// inside the "swarmforge up" process (see swarmforge/handoff-protocol.md);
// this package holds only the pure delivery logic, parameterized over a
// Notifier so it can be driven by real PTYs or a fake in tests.
package daemon

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/torratdev/swarmforge/internal/handoff"
	"github.com/torratdev/swarmforge/internal/state"
)

// Notifier wakes a recipient role up to check its inbox. The full TUI
// writes directly into the recipient's PTY master; tests inject a fake.
type Notifier interface {
	Notify(role string) error
}

// Event records one delivery outcome, for logging and for tests to assert
// against.
type Event struct {
	Time   time.Time
	Kind   string // "delivered", "error", "failed", "failed-to-archive"
	Path   string
	Detail string
}

// PollOnce scans every role's outbox once, delivering complete .handoff
// files to every recipient's inbox/new/ and moving the source to sent/ (or
// failed/ with a .error sidecar on any error). A malformed or undeliverable
// file never stops the loop from processing the rest.
func PollOnce(st state.State, notifier Notifier, now time.Time) []Event {
	roles := make(map[string]state.Role, len(st.Roles))
	for _, r := range st.Roles {
		roles[r.Name] = r
	}

	var events []Event
	for _, sender := range st.Roles {
		outboxDir := filepath.Join(sender.WorktreePath, ".swarmforge", "handoffs", "outbox")
		for _, path := range handoff.ListHandoffFiles(outboxDir) {
			events = append(events, deliverOne(path, roles, notifier, now)...)
		}
	}
	return events
}

func deliverOne(path string, roles map[string]state.Role, notifier Notifier, now time.Time) []Event {
	content, err := os.ReadFile(path)
	if err != nil {
		return failFile(path, err.Error(), now)
	}
	msg := handoff.Parse(string(content))
	to := strings.TrimSpace(msg.Headers["to"])
	if to == "" {
		return failFile(path, "missing to header", now)
	}

	var events []Event
	for _, recipient := range strings.Split(to, ",") {
		role, ok := roles[recipient]
		if !ok {
			return append(events, failFile(path, fmt.Sprintf("unknown recipient %s", recipient), now)...)
		}
		target := filepath.Join(role.WorktreePath, ".swarmforge", "handoffs", "inbox", "new", filepath.Base(path))
		if _, err := os.Stat(target); os.IsNotExist(err) {
			delivered := msg.WithHeader("recipient", recipient).WithHeader("enqueued_at", handoff.CreatedAt(now))
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return append(events, failFile(path, err.Error(), now)...)
			}
			if err := os.WriteFile(target, []byte(delivered.Render()), 0o644); err != nil {
				return append(events, failFile(path, err.Error(), now)...)
			}
		}
		if notifier != nil {
			if err := notifier.Notify(recipient); err != nil {
				events = append(events, Event{Time: now, Kind: "error", Path: path, Detail: "notify " + recipient + ": " + err.Error()})
			}
		}
	}

	sentDir := filepath.Join(filepath.Dir(filepath.Dir(path)), "sent")
	if err := moveWithCollision(path, sentDir, now); err != nil {
		return append(events, Event{Time: now, Kind: "error", Path: path, Detail: err.Error()})
	}
	return append(events, Event{Time: now, Kind: "delivered", Path: path})
}

func failFile(path, reason string, now time.Time) []Event {
	if err := os.WriteFile(path+".error", []byte(reason+"\n"), 0o644); err != nil {
		return []Event{{Time: now, Kind: "failed-to-archive", Path: path, Detail: err.Error()}}
	}
	failedDir := filepath.Join(filepath.Dir(filepath.Dir(path)), "failed")
	if err := moveWithCollision(path, failedDir, now); err != nil {
		return []Event{{Time: now, Kind: "failed-to-archive", Path: path, Detail: err.Error()}}
	}
	return []Event{{Time: now, Kind: "failed", Path: path, Detail: reason}}
}

// moveWithCollision moves path into destDir under its original basename,
// or a timestamp-prefixed name if that basename already exists there. It
// never silently overwrites: a true collision even on the prefixed name is
// an error.
func moveWithCollision(path, destDir string, now time.Time) error {
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return err
	}
	base := filepath.Base(path)
	target := filepath.Join(destDir, base)
	if _, err := os.Stat(target); err == nil {
		target = filepath.Join(destDir, handoff.IDTimestamp(now)+"_"+base)
		if _, err := os.Stat(target); err == nil {
			return fmt.Errorf("collision moving %s to %s", path, target)
		}
	}
	return os.Rename(path, target)
}
