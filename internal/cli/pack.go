package cli

import (
	"fmt"

	"github.com/torratdev/swarmforge/internal/pack"
)

// RunPackList prints the names and descriptions of every embedded pack.
func RunPackList(env Env) int {
	names, err := pack.Names()
	if err != nil {
		fmt.Fprintln(env.Stderr, err)
		return 1
	}
	for _, name := range names {
		def, err := pack.Load(name)
		if err != nil {
			fmt.Fprintln(env.Stderr, err)
			return 1
		}
		fmt.Fprintf(env.Stdout, "%s\t%s\n", name, def.Description)
	}
	return 0
}

// RunPackLint checks one (or, if name is empty, every) embedded pack for
// known trouble spots -- see pack.Lint.
func RunPackLint(env Env, name string) int {
	names := []string{name}
	if name == "" {
		var err error
		names, err = pack.Names()
		if err != nil {
			fmt.Fprintln(env.Stderr, err)
			return 1
		}
	}

	failed := false
	for _, n := range names {
		def, err := pack.Load(n)
		if err != nil {
			fmt.Fprintln(env.Stderr, err)
			failed = true
			continue
		}
		errs := pack.Lint(def)
		if len(errs) == 0 {
			fmt.Fprintf(env.Stdout, "%s: OK\n", n)
			continue
		}
		failed = true
		fmt.Fprintf(env.Stdout, "%s: FAIL\n", n)
		for _, e := range errs {
			fmt.Fprintln(env.Stdout, "  -", e)
		}
	}
	if failed {
		return 1
	}
	return 0
}
