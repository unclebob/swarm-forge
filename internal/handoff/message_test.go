package handoff

import (
	"strings"
	"testing"
)

func TestParseRenderRoundTrip(t *testing.T) {
	content := "id: 20260615T140531Z_000042_from_coder\n" +
		"from: coder\n" +
		"to: cleaner\n" +
		"priority: 50\n" +
		"type: git_handoff\n" +
		"role: coder\n" +
		"task: task-1-cave-setup\n" +
		"commit: a1b2c3d9e8\n" +
		"created_at: 2026-06-15T14:05:31Z\n" +
		"\n" +
		"Re-read your role and constitution.\n\nmerge_and_process coder a1b2c3d9e8"

	msg := Parse(content)
	if msg.Headers["from"] != "coder" || msg.Headers["to"] != "cleaner" {
		t.Fatalf("unexpected headers: %+v", msg.Headers)
	}
	if !strings.Contains(msg.Body, "merge_and_process coder a1b2c3d9e8") {
		t.Fatalf("body not preserved: %q", msg.Body)
	}

	rendered := msg.Render()
	// Preferred order: id from to recipient priority type role commit message created_at ...
	// "task" isn't in the preferred set, so it must land after created_at, alphabetically.
	idIdx := strings.Index(rendered, "id: ")
	fromIdx := strings.Index(rendered, "from: ")
	toIdx := strings.Index(rendered, "to: ")
	priorityIdx := strings.Index(rendered, "priority: ")
	typeIdx := strings.Index(rendered, "type: ")
	roleIdx := strings.Index(rendered, "role: ")
	commitIdx := strings.Index(rendered, "commit: ")
	createdIdx := strings.Index(rendered, "created_at: ")
	taskIdx := strings.Index(rendered, "task: ")

	for _, pair := range [][2]int{
		{idIdx, fromIdx}, {fromIdx, toIdx}, {toIdx, priorityIdx},
		{priorityIdx, typeIdx}, {typeIdx, roleIdx}, {roleIdx, commitIdx},
		{commitIdx, createdIdx}, {createdIdx, taskIdx},
	} {
		if pair[0] < 0 || pair[1] < 0 || pair[0] >= pair[1] {
			t.Fatalf("header ordering violated in rendered output:\n%s", rendered)
		}
	}

	// Round-tripping again must be stable.
	again := Parse(rendered)
	if again.Render() != rendered {
		t.Fatalf("render not stable across a second parse/render cycle")
	}
}

func TestRenderOmitsEmptyHeaders(t *testing.T) {
	msg := Message{Headers: map[string]string{"from": "coder", "to": ""}, Body: "x"}
	rendered := msg.Render()
	if strings.Contains(rendered, "to:") {
		t.Fatalf("empty-valued header should be omitted, got:\n%s", rendered)
	}
}

func TestWithHeaderDoesNotMutateOriginal(t *testing.T) {
	msg := Message{Headers: map[string]string{"from": "coder"}, Body: "x"}
	updated := msg.WithHeader("recipient", "cleaner")
	if _, ok := msg.Headers["recipient"]; ok {
		t.Fatalf("original message headers were mutated")
	}
	if updated.Headers["recipient"] != "cleaner" {
		t.Fatalf("WithHeader did not set the new header")
	}
}
