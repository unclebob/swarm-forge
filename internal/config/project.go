// Package config defines swarmforge.yaml, the declarative project config
// "swarmforge up" reads -- the data-driven replacement for the old
// line-based "window <role> <agent> <worktree> [mode] [args]" DSL. It's
// normally produced by "swarmforge init" from an embedded pack (see
// internal/pack) but is a plain, hand-editable YAML file afterward.
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

// FileName is swarmforge.yaml's location relative to a project root.
const FileName = "swarmforge.yaml"

// Role is one configured swarm participant.
type Role struct {
	Name        string   `yaml:"name"`
	Agent       string   `yaml:"agent"`
	Worktree    string   `yaml:"worktree"`
	ReceiveMode string   `yaml:"receive_mode"`
	ExtraArgs   []string `yaml:"extra_args"`
}

// Project is the full swarmforge.yaml document.
type Project struct {
	Name  string `yaml:"name"`
	Roles []Role `yaml:"roles"`
}

var AllowedAgents = map[string]bool{"claude": true, "codex": true, "copilot": true, "grok": true}

var roleNameRe = regexp.MustCompile(`^[^_]+$`)

// EffectiveReceiveMode returns the role's receive mode, defaulting to
// "task" when unset -- mirrors the original parse-config behavior where an
// omitted 5th field means task mode.
func (r Role) EffectiveReceiveMode() string {
	if r.ReceiveMode == "" {
		return "task"
	}
	return r.ReceiveMode
}

// IsSharedWorktree reports whether the role runs in the main working
// directory rather than a dedicated git worktree.
func (r Role) IsSharedWorktree() bool {
	return r.Worktree == "" || r.Worktree == "none" || r.Worktree == "master"
}

// WorktreePath resolves the role's absolute working directory given the
// project root.
func (r Role) WorktreePath(projectRoot string) string {
	if r.IsSharedWorktree() {
		return projectRoot
	}
	return filepath.Join(projectRoot, ".worktrees", r.Worktree)
}

// DisplayName renders "some-role" / "some_role" as "Some Role".
func (r Role) DisplayName() string {
	words := strings.FieldsFunc(r.Name, func(c rune) bool { return c == '-' || c == '_' })
	for i, w := range words {
		if w == "" {
			continue
		}
		words[i] = strings.ToUpper(w[:1]) + w[1:]
	}
	return strings.Join(words, " ")
}

// CleanupRole returns the first configured role: by convention (carried
// over unchanged from the original implementation) its process exiting
// tears the whole swarm down.
func (p Project) CleanupRole() (Role, bool) {
	if len(p.Roles) == 0 {
		return Role{}, false
	}
	return p.Roles[0], true
}

// Load reads and parses swarmforge.yaml from a project root. It does not
// validate; call Validate separately once a PromptExists checker is
// available.
func Load(projectRoot string) (Project, error) {
	b, err := os.ReadFile(filepath.Join(projectRoot, FileName))
	if err != nil {
		return Project{}, err
	}
	var p Project
	if err := yaml.Unmarshal(b, &p); err != nil {
		return Project{}, fmt.Errorf("parsing %s: %w", FileName, err)
	}
	return p, nil
}

// Save writes swarmforge.yaml to a project root.
func Save(projectRoot string, p Project) error {
	b, err := yaml.Marshal(p)
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(projectRoot, FileName), b, 0o644)
}

// PromptExists checks whether a role's prompt file is present; injected
// into Validate rather than baked in so config parsing stays testable
// without touching the filesystem.
type PromptExists func(role string) bool

// Validate checks a project config for internal consistency, mirroring
// the original parse-config rules: role names must be non-empty, unique,
// and underscore-free with a matching prompt file; agents must be one of
// the four supported backends; worktree names must be unique unless
// "none"/"master", and must not contain "/" or be "."/"..".
func (p Project) Validate(promptExists PromptExists) []string {
	var errs []string
	if len(p.Roles) == 0 {
		errs = append(errs, "project must configure at least one role")
		return errs
	}

	seenRoles := map[string]bool{}
	seenWorktrees := map[string]bool{}
	for _, r := range p.Roles {
		if r.Name == "" {
			errs = append(errs, "a role has an empty name")
			continue
		}
		if !roleNameRe.MatchString(r.Name) {
			errs = append(errs, fmt.Sprintf("role %q must not contain underscores", r.Name))
		}
		if seenRoles[r.Name] {
			errs = append(errs, fmt.Sprintf("role %q is configured more than once", r.Name))
		}
		seenRoles[r.Name] = true

		agent := strings.ToLower(r.Agent)
		if !AllowedAgents[agent] {
			errs = append(errs, fmt.Sprintf("role %q has unsupported agent %q (must be one of claude/codex/copilot/grok)", r.Name, r.Agent))
		}

		if r.Worktree == "" {
			errs = append(errs, fmt.Sprintf("role %q must set a worktree (use \"master\" for the main working directory)", r.Name))
		} else if !r.IsSharedWorktree() {
			if strings.Contains(r.Worktree, "/") {
				errs = append(errs, fmt.Sprintf("role %q worktree %q must not contain '/'", r.Name, r.Worktree))
			}
			if r.Worktree == "." || r.Worktree == ".." {
				errs = append(errs, fmt.Sprintf("role %q worktree must not be \".\" or \"..\"", r.Name))
			}
			if seenWorktrees[r.Worktree] {
				errs = append(errs, fmt.Sprintf("worktree %q is used by more than one role", r.Worktree))
			}
			seenWorktrees[r.Worktree] = true
		}

		mode := r.ReceiveMode
		if mode != "" && mode != "task" && mode != "batch" {
			errs = append(errs, fmt.Sprintf("role %q has invalid receive_mode %q (must be \"task\" or \"batch\")", r.Name, mode))
		}

		if promptExists != nil && r.Name != "" && !promptExists(r.Name) {
			errs = append(errs, fmt.Sprintf("role %q has no swarmforge/roles/%s.prompt file", r.Name, r.Name))
		}
	}
	return errs
}
