package handoff

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestParseDraftHeadersOnly(t *testing.T) {
	content := "type: git_handoff\nto: cleaner\npriority: 50\ntask: task-1\ncommit: a1b2c3d9e8\n"
	pd := ParseDraft(content)
	if len(pd.Errors) != 0 {
		t.Fatalf("unexpected errors: %v", pd.Errors)
	}
	if pd.Headers["type"] != "git_handoff" || pd.Headers["commit"] != "a1b2c3d9e8" {
		t.Fatalf("unexpected headers: %+v", pd.Headers)
	}
}

func TestParseDraftRejectsReservedAndUnknownAndBodyText(t *testing.T) {
	content := "type: note\nid: should-not-be-here\nbogus: field\nto: cleaner\npriority: 50\nmessage: hi\n\nnot allowed here\n"
	pd := ParseDraft(content)
	joined := strings.Join(pd.Errors, "\n")
	for _, want := range []string{"reserved", "unknown header", "headers only"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("expected an error containing %q, got: %v", want, pd.Errors)
		}
	}
}

func TestParseDraftRejectsDuplicateHeader(t *testing.T) {
	content := "type: note\ntype: note\nto: cleaner\npriority: 50\nmessage: hi\n"
	pd := ParseDraft(content)
	found := false
	for _, e := range pd.Errors {
		if strings.Contains(e, "duplicate header") {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected duplicate header error, got: %v", pd.Errors)
	}
}

func alwaysKnown(string) bool { return true }
func neverKnown(string) bool  { return false }

func fakeResolve(canonical string) CommitResolver {
	return func(abbrev string) (string, error) { return canonical, nil }
}

func TestValidateDraftGitHandoffValid(t *testing.T) {
	headers := map[string]string{
		"type": "git_handoff", "to": "cleaner", "priority": "50",
		"task": "task-1", "commit": "a1b2c3d9e8",
	}
	res := ValidateDraft(headers, alwaysKnown, fakeResolve("a1b2c3d9e8"))
	if len(res.Errors) != 0 {
		t.Fatalf("unexpected errors: %v", res.Errors)
	}
	if res.CanonicalCommit != "a1b2c3d9e8" {
		t.Fatalf("canonical commit not propagated: %q", res.CanonicalCommit)
	}
	if len(res.Recipients) != 1 || res.Recipients[0] != "cleaner" {
		t.Fatalf("unexpected recipients: %v", res.Recipients)
	}
}

func TestValidateDraftNoteValid(t *testing.T) {
	headers := map[string]string{"type": "note", "to": "architect,QA", "priority": "70", "message": "short note"}
	res := ValidateDraft(headers, alwaysKnown, nil)
	if len(res.Errors) != 0 {
		t.Fatalf("unexpected errors: %v", res.Errors)
	}
	if len(res.Recipients) != 2 {
		t.Fatalf("expected 2 recipients, got %v", res.Recipients)
	}
}

func TestValidateDraftErrors(t *testing.T) {
	cases := []struct {
		name    string
		headers map[string]string
		roles   RoleChecker
		want    string
	}{
		{"bad priority", map[string]string{"type": "note", "to": "a", "priority": "urgent", "message": "x"}, alwaysKnown, "must be two digits"},
		{"bad type", map[string]string{"type": "bogus", "to": "a", "priority": "50"}, alwaysKnown, "must be one of git_handoff or note"},
		{"missing commit", map[string]string{"type": "git_handoff", "to": "a", "priority": "50", "task": "t"}, alwaysKnown, "commit' for git_handoff"},
		{"short commit", map[string]string{"type": "git_handoff", "to": "a", "priority": "50", "task": "t", "commit": "abc"}, alwaysKnown, "exactly 10 hexadecimal"},
		{"note too long", map[string]string{"type": "note", "to": "a", "priority": "50", "message": strings.Repeat("x", 81)}, alwaysKnown, "no longer than 80"},
		{"underscore role", map[string]string{"type": "note", "to": "bad_role", "priority": "50", "message": "x"}, alwaysKnown, "may not contain underscores"},
		{"unknown recipient", map[string]string{"type": "note", "to": "ghost", "priority": "50", "message": "x"}, neverKnown, "Unknown recipient role"},
		{"duplicate recipient", map[string]string{"type": "note", "to": "a,a", "priority": "50", "message": "x"}, alwaysKnown, "Duplicate recipient"},
		{"message on git_handoff", map[string]string{"type": "git_handoff", "to": "a", "priority": "50", "task": "t", "commit": "a1b2c3d9e8", "message": "nope"}, alwaysKnown, "'message' is only allowed for note"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			res := ValidateDraft(tc.headers, tc.roles, fakeResolve("a1b2c3d9e8"))
			joined := strings.Join(res.Errors, "\n")
			if !strings.Contains(joined, tc.want) {
				t.Fatalf("expected error containing %q, got: %v", tc.want, res.Errors)
			}
		})
	}
}

func TestValidateDraftCommitResolutionError(t *testing.T) {
	headers := map[string]string{"type": "git_handoff", "to": "a", "priority": "50", "task": "t", "commit": "a1b2c3d9e8"}
	resolver := func(string) (string, error) { return "", os.ErrNotExist }
	res := ValidateDraft(headers, alwaysKnown, resolver)
	if len(res.Errors) == 0 {
		t.Fatalf("expected an error when commit resolution fails")
	}
}

func TestWriteDraftGitHandoff(t *testing.T) {
	dir := t.TempDir()
	handoffsDir := filepath.Join(dir, ".swarmforge", "handoffs")
	now := time.Date(2026, 6, 15, 14, 5, 31, 0, time.UTC)

	headers := map[string]string{
		"type": "git_handoff", "to": "cleaner", "priority": "50",
		"task": "task-1-cave-setup", "commit": "a1b2c3d9e8",
	}
	path, err := WriteDraft(handoffsDir, headers, []string{"cleaner"}, "coder", "a1b2c3d9e8", now)
	if err != nil {
		t.Fatalf("WriteDraft: %v", err)
	}
	base := filepath.Base(path)
	if base != "50_20260615T140531Z_000001_from_coder_to_cleaner.handoff" {
		t.Fatalf("unexpected filename: %s", base)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("reading written file: %v", err)
	}
	got := string(content)
	for _, want := range []string{
		"id: 20260615T140531Z_000001_from_coder\n",
		"from: coder\n",
		"to: cleaner\n",
		"priority: 50\n",
		"type: git_handoff\n",
		"role: coder\n",
		"task: task-1-cave-setup\n",
		"commit: a1b2c3d9e8\n",
		"created_at: 2026-06-15T14:05:31Z\n",
		"merge_and_process coder a1b2c3d9e8",
	} {
		if !strings.Contains(got, want) {
			t.Fatalf("written file missing %q, got:\n%s", want, got)
		}
	}
	// tmp file must not be left behind
	if _, err := os.Stat(filepath.Join(handoffsDir, "outbox", "tmp", base+".tmp")); !os.IsNotExist(err) {
		t.Fatalf("temp file was not cleaned up (renamed away)")
	}
}

func TestWriteDraftSequenceIncrements(t *testing.T) {
	dir := t.TempDir()
	handoffsDir := filepath.Join(dir, ".swarmforge", "handoffs")
	now := time.Date(2026, 6, 15, 14, 5, 31, 0, time.UTC)
	headers := map[string]string{"type": "note", "to": "a", "priority": "50", "message": "hi"}

	p1, err := WriteDraft(handoffsDir, headers, []string{"a"}, "coder", "", now)
	if err != nil {
		t.Fatal(err)
	}
	p2, err := WriteDraft(handoffsDir, headers, []string{"a"}, "coder", "", now)
	if err != nil {
		t.Fatal(err)
	}
	if p1 == p2 {
		t.Fatalf("expected distinct filenames on successive writes at the same timestamp, got %s twice", p1)
	}
}
