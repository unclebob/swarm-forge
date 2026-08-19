package pack

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/TorratDev/swarm-forge/internal/config"
)

const constitutionPointer = "# SwarmForge Constitution\n\n" +
	"This file takes precedence over article files.\n" +
	"Read and obey every file in `swarmforge/constitution/articles/`.\n"

// Lint checks a pack definition for known trouble spots. Currently just
// the "handoffs" base article being silently dropped without a
// replacement -- an oversight found in the original four-pack/six-pack
// branches, which every new pack must avoid repeating.
func Lint(def Definition) []string {
	var errs []string

	hasHandoffs := false
	for _, a := range def.Constitution.EffectiveBase() {
		if a == "handoffs" {
			hasHandoffs = true
		}
	}
	if !hasHandoffs {
		errs = append(errs, `constitution.base omits "handoffs" with no replacement; every pack must include it or an explicit override`)
	}

	if len(def.Roles) == 0 {
		errs = append(errs, "pack defines no roles")
	}
	seen := map[string]bool{}
	for _, r := range def.Roles {
		if seen[r.Name] {
			errs = append(errs, fmt.Sprintf("role %q is defined more than once", r.Name))
		}
		seen[r.Name] = true
	}
	return errs
}

// ToProject converts a pack definition into the swarmforge.yaml project
// config "swarmforge up" reads.
func (def Definition) ToProject() config.Project {
	p := config.Project{Name: def.Name}
	for _, r := range def.Roles {
		p.Roles = append(p.Roles, config.Role{
			Name: r.Name, Agent: r.Agent, Worktree: r.Worktree,
			ReceiveMode: r.ReceiveMode, ExtraArgs: r.ExtraArgs,
		})
	}
	return p
}

// RenderRolePrompt renders one role's swarmforge/roles/<name>.prompt
// content from its sections, in the "You are the X." / "## Heading"
// shape observed across the original hand-written role prompts.
func RenderRolePrompt(r RoleDef) (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "You are the %s.\n", r.Name)
	for _, s := range r.Sections {
		b.WriteString("\n## " + s.Title + "\n")
		body, err := renderSectionBody(s)
		if err != nil {
			return "", fmt.Errorf("role %q section %q: %w", r.Name, s.Title, err)
		}
		b.WriteString(body)
		if !strings.HasSuffix(body, "\n") {
			b.WriteString("\n")
		}
	}
	return b.String(), nil
}

func renderSectionBody(s Section) (string, error) {
	switch {
	case s.Handoff != nil:
		return renderHandoff(*s.Handoff), nil
	case s.Text != "":
		return s.Text, nil
	default:
		var parts []string
		for _, slug := range s.Blocks {
			content, err := block(slug)
			if err != nil {
				return "", err
			}
			parts = append(parts, strings.TrimRight(content, "\n"))
		}
		return strings.Join(parts, "\n"), nil
	}
}

// renderHandoff renders a "## Handoff" section body: any extra steps as
// bullets, then a final bullet naming the recipients, priority, and
// message type -- rendered consistently instead of hand-written per role.
func renderHandoff(h Handoff) string {
	var b strings.Builder
	for _, step := range h.Steps {
		b.WriteString("- " + step + "\n")
	}
	fmt.Fprintf(&b, "- Send a `%s` to `%s` with priority `%s`.\n", h.EffectiveType(), strings.Join(h.To, ","), h.Priority)
	return b.String()
}

// Generate materializes a pack definition into a real, editable project
// directory: swarmforge.yaml, one swarmforge/roles/<role>.prompt per
// role, swarmforge/constitution.prompt, the pack's base constitution
// articles, and its project.prompt.
func Generate(def Definition, packName, destDir string) error {
	if err := config.Save(destDir, def.ToProject()); err != nil {
		return err
	}

	rolesDir := filepath.Join(destDir, "swarmforge", "roles")
	if err := os.MkdirAll(rolesDir, 0o755); err != nil {
		return err
	}
	for _, r := range def.Roles {
		content, err := RenderRolePrompt(r)
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(rolesDir, r.Name+".prompt"), []byte(content), 0o644); err != nil {
			return err
		}
	}

	articlesDir := filepath.Join(destDir, "swarmforge", "constitution", "articles")
	if err := os.MkdirAll(articlesDir, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(destDir, "swarmforge", "constitution.prompt"), []byte(constitutionPointer), 0o644); err != nil {
		return err
	}
	for _, slug := range def.Constitution.EffectiveBase() {
		content, err := baseArticle(slug)
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(articlesDir, slug+".prompt"), []byte(content), 0o644); err != nil {
			return err
		}
	}
	for _, rel := range def.Constitution.Extra {
		content, err := packLocalFile(packName, rel)
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(articlesDir, filepath.Base(rel)), []byte(content), 0o644); err != nil {
			return err
		}
	}
	return nil
}
