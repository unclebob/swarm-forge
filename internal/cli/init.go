package cli

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/torratdev/swarmforge/internal/pack"
)

// RunInit materializes an embedded pack definition into destDir:
// swarmforge.yaml, one role prompt per role, and constitution files. It
// refuses to run over an existing project (a swarmforge.yaml already
// present in destDir) to avoid clobbering hand-edits.
func RunInit(env Env, packName, destDir string) int {
	if packName == "" {
		fmt.Fprintln(env.Stderr, "swarmforge init: --pack is required")
		if names, err := pack.Names(); err == nil && len(names) > 0 {
			fmt.Fprintln(env.Stderr, "Available packs:", strings.Join(names, ", "))
		}
		return 1
	}

	def, err := pack.Load(packName)
	if err != nil {
		fmt.Fprintln(env.Stderr, err)
		return 1
	}
	if errs := pack.Lint(def); len(errs) != 0 {
		fmt.Fprintln(env.Stderr, "pack", packName, "failed lint:")
		for _, e := range errs {
			fmt.Fprintln(env.Stderr, "-", e)
		}
		return 1
	}

	if _, err := os.Stat(filepath.Join(destDir, "swarmforge.yaml")); err == nil {
		fmt.Fprintln(env.Stderr, "swarmforge.yaml already exists in", destDir, "-- refusing to overwrite an existing project")
		return 1
	}

	if err := os.MkdirAll(destDir, 0o755); err != nil {
		fmt.Fprintln(env.Stderr, err)
		return 1
	}
	if err := pack.Generate(def, packName, destDir); err != nil {
		fmt.Fprintln(env.Stderr, "generating project:", err)
		return 1
	}

	fmt.Fprintln(env.Stdout, "Initialized", packName, "in", destDir)
	for _, r := range def.Roles {
		fmt.Fprintf(env.Stdout, "  - %s (%s)\n", r.Name, r.Agent)
	}
	return 0
}
