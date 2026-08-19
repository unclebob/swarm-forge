package config

import (
	"path/filepath"
	"strings"
	"testing"
)

func alwaysHasPrompt(string) bool { return true }

func TestValidateAcceptsGoodProject(t *testing.T) {
	p := Project{Roles: []Role{
		{Name: "coder", Agent: "claude", Worktree: "master", ReceiveMode: "task"},
		{Name: "cleaner", Agent: "claude", Worktree: "cleaner", ReceiveMode: "batch"},
	}}
	if errs := p.Validate(alwaysHasPrompt); len(errs) != 0 {
		t.Fatalf("unexpected errors: %v", errs)
	}
}

func TestValidateRejectsEmptyProject(t *testing.T) {
	if errs := (Project{}).Validate(alwaysHasPrompt); len(errs) == 0 {
		t.Fatalf("expected an error for a project with no roles")
	}
}

func TestValidateRejectsUnderscoreRoleName(t *testing.T) {
	p := Project{Roles: []Role{{Name: "bad_role", Agent: "claude", Worktree: "master"}}}
	errs := p.Validate(alwaysHasPrompt)
	if !containsSubstr(errs, "underscores") {
		t.Fatalf("expected underscore error, got: %v", errs)
	}
}

func TestValidateRejectsDuplicateRoleName(t *testing.T) {
	p := Project{Roles: []Role{
		{Name: "coder", Agent: "claude", Worktree: "master"},
		{Name: "coder", Agent: "claude", Worktree: "second"},
	}}
	errs := p.Validate(alwaysHasPrompt)
	if !containsSubstr(errs, "more than once") {
		t.Fatalf("expected duplicate-role error, got: %v", errs)
	}
}

func TestValidateRejectsUnsupportedAgent(t *testing.T) {
	p := Project{Roles: []Role{{Name: "coder", Agent: "gpt5", Worktree: "master"}}}
	errs := p.Validate(alwaysHasPrompt)
	if !containsSubstr(errs, "unsupported agent") {
		t.Fatalf("expected unsupported-agent error, got: %v", errs)
	}
}

func TestValidateRejectsDuplicateWorktree(t *testing.T) {
	p := Project{Roles: []Role{
		{Name: "coder", Agent: "claude", Worktree: "shared"},
		{Name: "cleaner", Agent: "claude", Worktree: "shared"},
	}}
	errs := p.Validate(alwaysHasPrompt)
	if !containsSubstr(errs, "more than one role") {
		t.Fatalf("expected duplicate-worktree error, got: %v", errs)
	}
}

func TestValidateAllowsSharedNoneMasterWorktrees(t *testing.T) {
	p := Project{Roles: []Role{
		{Name: "a", Agent: "claude", Worktree: "master"},
		{Name: "b", Agent: "claude", Worktree: "none"},
	}}
	if errs := p.Validate(alwaysHasPrompt); len(errs) != 0 {
		t.Fatalf("master/none should not collide as duplicate worktrees: %v", errs)
	}
}

func TestValidateRejectsBadWorktreeNames(t *testing.T) {
	for _, wt := range []string{"a/b", ".", ".."} {
		p := Project{Roles: []Role{{Name: "coder", Agent: "claude", Worktree: wt}}}
		if errs := p.Validate(alwaysHasPrompt); len(errs) == 0 {
			t.Fatalf("expected an error for worktree %q", wt)
		}
	}
}

func TestValidateRejectsMissingPromptFile(t *testing.T) {
	p := Project{Roles: []Role{{Name: "coder", Agent: "claude", Worktree: "master"}}}
	errs := p.Validate(func(string) bool { return false })
	if !containsSubstr(errs, "no swarmforge/roles/coder.prompt") {
		t.Fatalf("expected missing-prompt error, got: %v", errs)
	}
}

func TestReceiveModeDefaultsToTask(t *testing.T) {
	if (Role{}).EffectiveReceiveMode() != "task" {
		t.Fatalf("expected default receive mode to be task")
	}
	if (Role{ReceiveMode: "batch"}).EffectiveReceiveMode() != "batch" {
		t.Fatalf("explicit receive mode not honored")
	}
}

func TestWorktreePathResolution(t *testing.T) {
	root := "/proj"
	if got := (Role{Worktree: "master"}).WorktreePath(root); got != root {
		t.Fatalf("master worktree should resolve to project root, got %q", got)
	}
	if got := (Role{Worktree: "cleaner"}).WorktreePath(root); got != filepath.Join(root, ".worktrees", "cleaner") {
		t.Fatalf("unexpected worktree path: %q", got)
	}
}

func TestDisplayName(t *testing.T) {
	if got := (Role{Name: "coder"}).DisplayName(); got != "Coder" {
		t.Fatalf("got %q", got)
	}
	if got := (Role{Name: "release-manager"}).DisplayName(); got != "Release Manager" {
		t.Fatalf("got %q", got)
	}
}

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	p := Project{Name: "two-pack", Roles: []Role{
		{Name: "coder", Agent: "claude", Worktree: "master", ReceiveMode: "task", ExtraArgs: []string{"--model", "haiku"}},
	}}
	if err := Save(dir, p); err != nil {
		t.Fatalf("Save: %v", err)
	}
	loaded, err := Load(dir)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if len(loaded.Roles) != 1 || loaded.Roles[0].Name != "coder" || loaded.Roles[0].ExtraArgs[1] != "haiku" {
		t.Fatalf("round trip lost data: %+v", loaded)
	}
}

func containsSubstr(errs []string, want string) bool {
	for _, e := range errs {
		if strings.Contains(e, want) {
			return true
		}
	}
	return false
}
