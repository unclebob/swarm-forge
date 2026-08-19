package handoff

import (
	"fmt"
	"math/rand"
	"sort"
	"testing"
	"time"
)

func TestFilenameFormat(t *testing.T) {
	got := Filename("00", "20260615T140531Z", "000042", "architect", []string{"coder", "cleaner", "QA"})
	want := "00_20260615T140531Z_000042_from_architect_to_coder_cleaner_QA.handoff"
	if got != want {
		t.Fatalf("Filename() = %q, want %q", got, want)
	}
}

func TestIDFormat(t *testing.T) {
	got := ID("20260615T140531Z", "000042", "coder")
	want := "20260615T140531Z_000042_from_coder"
	if got != want {
		t.Fatalf("ID() = %q, want %q", got, want)
	}
}

func TestCreatedAtFormat(t *testing.T) {
	ts := time.Date(2026, 6, 15, 14, 5, 31, 0, time.UTC)
	if got := CreatedAt(ts); got != "2026-06-15T14:05:31Z" {
		t.Fatalf("CreatedAt() = %q", got)
	}
	if got := IDTimestamp(ts); got != "20260615T140531Z" {
		t.Fatalf("IDTimestamp() = %q", got)
	}
}

// TestFilenameSortOrder is the property test the queue's entire ordering
// guarantee rests on: filenames must sort lexicographically in the same
// order handoffs were enqueued, across priority, timestamp, and sequence
// boundaries.
func TestFilenameSortOrder(t *testing.T) {
	type entry struct {
		priority  int
		timestamp time.Time
		sequence  int
	}

	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	rng := rand.New(rand.NewSource(1))

	var entries []entry
	for i := 0; i < 500; i++ {
		entries = append(entries, entry{
			priority:  rng.Intn(100),
			timestamp: base.Add(time.Duration(rng.Intn(1000)) * time.Second),
			sequence:  rng.Intn(1000),
		})
	}

	// Expected order per the protocol: priority asc, then timestamp asc,
	// then sequence asc.
	expected := append([]entry(nil), entries...)
	sort.SliceStable(expected, func(i, j int) bool {
		a, b := expected[i], expected[j]
		if a.priority != b.priority {
			return a.priority < b.priority
		}
		if !a.timestamp.Equal(b.timestamp) {
			return a.timestamp.Before(b.timestamp)
		}
		return a.sequence < b.sequence
	})

	names := make([]string, len(entries))
	for i, e := range entries {
		names[i] = Filename(fmt.Sprintf("%02d", e.priority), IDTimestamp(e.timestamp), fmt.Sprintf("%06d", e.sequence), "sender", []string{"recipient"})
	}
	sortedNames := append([]string(nil), names...)
	sort.Strings(sortedNames)

	expectedNames := make([]string, len(expected))
	for i, e := range expected {
		expectedNames[i] = Filename(fmt.Sprintf("%02d", e.priority), IDTimestamp(e.timestamp), fmt.Sprintf("%06d", e.sequence), "sender", []string{"recipient"})
	}

	for i := range sortedNames {
		if sortedNames[i] != expectedNames[i] {
			t.Fatalf("lexicographic sort diverged from enqueue-order sort at index %d:\n  got  %s\n  want %s", i, sortedNames[i], expectedNames[i])
		}
	}
}
