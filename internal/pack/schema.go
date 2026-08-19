// Package pack implements SwarmForge's declarative pack definitions --
// the replacement for the old three-branch (two-pack/four-pack/six-pack)
// distribution model. A Definition is embedded in the binary (go:embed)
// and materialized into a real, editable project directory by
// "swarmforge init --pack <name>": swarmforge.yaml, role prompts, and
// constitution files.
package pack

// Definition is one pack.yaml document.
type Definition struct {
	Name         string       `yaml:"name"`
	Description  string       `yaml:"description"`
	Constitution Constitution `yaml:"constitution"`
	Roles        []RoleDef    `yaml:"roles"`
}

// Constitution describes which base articles a pack pulls in and its
// project-shape overlay.
type Constitution struct {
	// Base lists shared article slugs (from internal/pack/articles/base)
	// every pack should include. Defaults to [engineering, handoffs,
	// workflow] when empty -- a pack must opt out of "handoffs"
	// explicitly (see Lint), fixing an oversight in the original
	// four-pack/six-pack branches, which dropped it with no replacement.
	Base []string `yaml:"base"`
	// Project names the pack-local file (relative to the pack's own
	// directory) rendered as swarmforge/constitution/articles/project.prompt.
	Project string `yaml:"project"`
}

// DefaultBaseArticles is used when a pack's Constitution.Base is empty.
var DefaultBaseArticles = []string{"engineering", "handoffs", "workflow"}

// EffectiveBase returns c.Base, defaulting to DefaultBaseArticles.
func (c Constitution) EffectiveBase() []string {
	if len(c.Base) > 0 {
		return c.Base
	}
	return DefaultBaseArticles
}

// RoleDef is one role within a pack: both its swarmforge.yaml entry and
// its rendered role prompt are derived from this.
type RoleDef struct {
	Name        string    `yaml:"name"`
	Agent       string    `yaml:"agent"`
	Worktree    string    `yaml:"worktree"`
	ReceiveMode string    `yaml:"receive_mode"`
	ExtraArgs   []string  `yaml:"extra_args"`
	Sections    []Section `yaml:"sections"`
}

// Section is one "## <Title>" block of a generated role prompt, built
// either from reusable block references, one-off inline text, or a
// structured Handoff -- never more than one of Blocks/Text/Handoff.
type Section struct {
	Title   string   `yaml:"title"`
	Blocks  []string `yaml:"blocks"`
	Text    string   `yaml:"text"`
	Handoff *Handoff `yaml:"handoff"`
}

// Handoff renders a role's "## Handoff" section consistently from
// structured data instead of hand-written per-role prose.
type Handoff struct {
	// Steps are extra bullets rendered before the final "Send a
	// `<type>` to ... with priority ..." line, e.g. "Run unit tests and
	// relevant local verification.", "Commit the behavior change."
	Steps    []string `yaml:"steps"`
	To       []string `yaml:"to"`
	Priority string   `yaml:"priority"`
	// Type is "git_handoff" (default) or "note".
	Type string `yaml:"type"`
}

// EffectiveType returns h.Type, defaulting to "git_handoff".
func (h Handoff) EffectiveType() string {
	if h.Type == "" {
		return "git_handoff"
	}
	return h.Type
}
