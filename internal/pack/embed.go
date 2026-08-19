package pack

import (
	"embed"
	"fmt"
	"io/fs"

	"gopkg.in/yaml.v3"
)

//go:embed definitions
var definitionsFS embed.FS

//go:embed blocks
var blocksFS embed.FS

//go:embed articles/base
var articlesFS embed.FS

// Names lists every embedded pack's name, sorted by directory name.
func Names() ([]string, error) {
	entries, err := fs.ReadDir(definitionsFS, "definitions")
	if err != nil {
		return nil, err
	}
	var names []string
	for _, e := range entries {
		if e.IsDir() {
			names = append(names, e.Name())
		}
	}
	return names, nil
}

// Load parses an embedded pack definition by name (its directory name
// under internal/pack/definitions).
func Load(name string) (Definition, error) {
	b, err := definitionsFS.ReadFile(fmt.Sprintf("definitions/%s/pack.yaml", name))
	if err != nil {
		return Definition{}, fmt.Errorf("unknown pack %q: %w", name, err)
	}
	var def Definition
	if err := yaml.Unmarshal(b, &def); err != nil {
		return Definition{}, fmt.Errorf("parsing pack %q: %w", name, err)
	}
	return def, nil
}

// block returns a named block's raw Markdown content.
func block(slug string) (string, error) {
	b, err := blocksFS.ReadFile(fmt.Sprintf("blocks/%s.md", slug))
	if err != nil {
		return "", fmt.Errorf("unknown block %q: %w", slug, err)
	}
	return string(b), nil
}

// baseArticle returns a named base constitution article's raw content.
func baseArticle(slug string) (string, error) {
	b, err := articlesFS.ReadFile(fmt.Sprintf("articles/base/%s.prompt", slug))
	if err != nil {
		return "", fmt.Errorf("unknown base article %q: %w", slug, err)
	}
	return string(b), nil
}

// packLocalFile reads a file relative to a named pack's own directory
// (e.g. its project.prompt).
func packLocalFile(packName, relPath string) (string, error) {
	b, err := definitionsFS.ReadFile(fmt.Sprintf("definitions/%s/%s", packName, relPath))
	if err != nil {
		return "", fmt.Errorf("pack %q: reading %q: %w", packName, relPath, err)
	}
	return string(b), nil
}
