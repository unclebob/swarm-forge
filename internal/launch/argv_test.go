package launch

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestInstructionFileContent(t *testing.T) {
	dir := t.TempDir()
	path, err := InstructionFile(dir, "coder")
	if err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	got := string(content)
	if !strings.Contains(got, "swarmforge/constitution.prompt") || !strings.Contains(got, "swarmforge/roles/coder.prompt") {
		t.Fatalf("instruction file missing expected references:\n%s", got)
	}
}

func spec() Spec {
	return Spec{
		Role: "coder", DisplayName: "Coder", WorktreePath: "/proj/.worktrees/coder",
		ExtraArgs: []string{"--model", "haiku"}, PromptFile: "/proj/.swarmforge/prompts/coder.md",
		PromptText: "read your role prompt",
	}
}

func TestBuildClaudeArgvDefaultPermissionMode(t *testing.T) {
	argv, err := BuildArgv("claude", spec())
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"claude",
		"--append-system-prompt-file", "/proj/.swarmforge/prompts/coder.md",
		"--permission-mode", "acceptEdits",
		"-n", "SwarmForge Coder",
		"--model", "haiku",
		"read your role prompt",
	}
	if !reflect.DeepEqual(argv, want) {
		t.Fatalf("argv = %#v\nwant %#v", argv, want)
	}
}

func TestBuildClaudeArgvYoloEnablesBypassPermissions(t *testing.T) {
	s := spec()
	s.ExtraArgs = []string{"--model", "haiku", "--yolo"}
	argv, err := BuildArgv("claude", s)
	if err != nil {
		t.Fatal(err)
	}
	if !containsAdjacent(argv, "--permission-mode", "bypassPermissions") {
		t.Fatalf("expected bypassPermissions with --yolo, got: %v", argv)
	}
}

func TestBuildClaudeArgvAlwaysApproveEnablesBypassPermissions(t *testing.T) {
	s := spec()
	s.ExtraArgs = []string{"--always-approve"}
	argv, _ := BuildArgv("claude", s)
	if !containsAdjacent(argv, "--permission-mode", "bypassPermissions") {
		t.Fatalf("expected bypassPermissions with --always-approve, got: %v", argv)
	}
}

func TestBuildClaudeArgvExplicitBypassPermissions(t *testing.T) {
	s := spec()
	s.ExtraArgs = []string{"--permission-mode", "bypassPermissions"}
	argv, _ := BuildArgv("claude", s)
	if !containsAdjacent(argv, "--permission-mode", "bypassPermissions") {
		t.Fatalf("expected bypassPermissions passed through explicitly, got: %v", argv)
	}
}

func TestBuildGrokArgvDefaultsToAcceptEdits(t *testing.T) {
	argv, err := BuildArgv("grok", spec())
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"grok", "--cwd", "/proj/.worktrees/coder",
		"--permission-mode", "acceptEdits",
		"--model", "haiku",
		"--rules", "read your role prompt",
		"--verbatim", "read your role prompt",
	}
	if !reflect.DeepEqual(argv, want) {
		t.Fatalf("argv = %#v\nwant %#v", argv, want)
	}
}

func TestBuildCodexArgvHasNoPermissionMode(t *testing.T) {
	argv, err := BuildArgv("codex", spec())
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"codex", "-C", "/proj/.worktrees/coder", "--model", "haiku", "read your role prompt"}
	if !reflect.DeepEqual(argv, want) {
		t.Fatalf("argv = %#v\nwant %#v", argv, want)
	}
	if containsAdjacent(argv, "--permission-mode", "acceptEdits") || containsAdjacent(argv, "--permission-mode", "bypassPermissions") {
		t.Fatalf("codex should never get an injected permission-mode flag: %v", argv)
	}
}

func TestBuildCopilotArgv(t *testing.T) {
	argv, err := BuildArgv("copilot", spec())
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"copilot", "-C", "/proj/.worktrees/coder",
		"--name", "SwarmForge Coder",
		"--model", "haiku",
		"-i", "read your role prompt",
	}
	if !reflect.DeepEqual(argv, want) {
		t.Fatalf("argv = %#v\nwant %#v", argv, want)
	}
}

func TestBuildArgvUnsupportedAgent(t *testing.T) {
	if _, err := BuildArgv("bogus", spec()); err == nil {
		t.Fatalf("expected an error for an unsupported agent")
	}
}

func containsAdjacent(argv []string, a, b string) bool {
	for i := 0; i+1 < len(argv); i++ {
		if argv[i] == a && argv[i+1] == b {
			return true
		}
	}
	return false
}

func TestInstructionFilePathIsUnderStateDirPrompts(t *testing.T) {
	dir := t.TempDir()
	path, err := InstructionFile(dir, "cleaner")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(dir, "prompts", "cleaner.md")
	if path != want {
		t.Fatalf("path = %q, want %q", path, want)
	}
}
