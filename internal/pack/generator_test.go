package pack

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNamesIncludesTwoPack(t *testing.T) {
	names, err := Names()
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, n := range names {
		if n == "two-pack" {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected \"two-pack\" among embedded packs, got %v", names)
	}
}

func TestLoadTwoPack(t *testing.T) {
	def, err := Load("two-pack")
	if err != nil {
		t.Fatal(err)
	}
	if def.Name != "two-pack" || len(def.Roles) != 2 {
		t.Fatalf("unexpected definition: %+v", def)
	}
	if errs := Lint(def); len(errs) != 0 {
		t.Fatalf("two-pack should lint clean, got: %v", errs)
	}
}

func TestLoadUnknownPack(t *testing.T) {
	if _, err := Load("nonexistent"); err == nil {
		t.Fatalf("expected an error loading an unknown pack")
	}
}

func TestLintCatchesMissingHandoffsArticle(t *testing.T) {
	def := Definition{
		Constitution: Constitution{Base: []string{"engineering", "workflow"}},
		Roles:        []RoleDef{{Name: "coder"}},
	}
	errs := Lint(def)
	found := false
	for _, e := range errs {
		if strings.Contains(e, "handoffs") {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected a lint error about the missing handoffs article, got: %v", errs)
	}
}

func TestLintCatchesDuplicateRoles(t *testing.T) {
	def := Definition{
		Constitution: Constitution{Base: DefaultBaseArticles},
		Roles:        []RoleDef{{Name: "coder"}, {Name: "coder"}},
	}
	errs := Lint(def)
	if !containsSubstr(errs, "more than once") {
		t.Fatalf("expected duplicate-role lint error, got: %v", errs)
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

func TestRenderRolePromptCoder(t *testing.T) {
	def, err := Load("two-pack")
	if err != nil {
		t.Fatal(err)
	}
	coder := def.Roles[0]
	rendered, err := RenderRolePrompt(coder)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"You are the coder.",
		"## Owns",
		"Implement requested behavior in the project language.",
		"## Implementation",
		"Use TDD to specify behavior before implementation.",
		"## Does Not Own",
		"CRAP, DRY, or language mutation.",
		"## Handoff",
		"Run unit tests and relevant local verification.",
		"Commit the behavior change.",
		"Send a `git_handoff` to `cleaner` with priority `50`.",
	} {
		if !strings.Contains(rendered, want) {
			t.Fatalf("rendered coder prompt missing %q, got:\n%s", want, rendered)
		}
	}
}

func TestGenerateTwoPackWritesAllExpectedFiles(t *testing.T) {
	def, err := Load("two-pack")
	if err != nil {
		t.Fatal(err)
	}
	dest := t.TempDir()
	if err := Generate(def, "two-pack", dest); err != nil {
		t.Fatalf("Generate: %v", err)
	}

	for _, rel := range []string{
		"swarmforge.yaml",
		"swarmforge/constitution.prompt",
		"swarmforge/constitution/articles/engineering.prompt",
		"swarmforge/constitution/articles/handoffs.prompt",
		"swarmforge/constitution/articles/workflow.prompt",
		"swarmforge/constitution/articles/project.prompt",
		"swarmforge/roles/coder.prompt",
		"swarmforge/roles/cleaner.prompt",
	} {
		path := filepath.Join(dest, rel)
		if _, err := os.Stat(path); err != nil {
			t.Fatalf("expected %s to exist: %v", rel, err)
		}
	}

	yamlContent, err := os.ReadFile(filepath.Join(dest, "swarmforge.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(yamlContent), "name: coder") || !strings.Contains(string(yamlContent), "name: cleaner") {
		t.Fatalf("generated swarmforge.yaml missing roles:\n%s", yamlContent)
	}
}

func TestGeneratedProjectPassesConfigValidation(t *testing.T) {
	def, err := Load("two-pack")
	if err != nil {
		t.Fatal(err)
	}
	dest := t.TempDir()
	if err := Generate(def, "two-pack", dest); err != nil {
		t.Fatal(err)
	}

	proj := def.ToProject()
	promptExists := func(role string) bool {
		_, err := os.Stat(filepath.Join(dest, "swarmforge", "roles", role+".prompt"))
		return err == nil
	}
	if errs := proj.Validate(promptExists); len(errs) != 0 {
		t.Fatalf("generated project should validate cleanly, got: %v", errs)
	}
}
