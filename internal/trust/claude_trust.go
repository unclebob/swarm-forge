// Package trust patches Claude Code's own workspace-trust state so
// unattended "claude" agent processes launched by SwarmForge don't block
// on the interactive first-run trust dialog.
package trust

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// Path returns the Claude Code config file trust state lives in.
func Path(home string) string {
	return filepath.Join(home, ".claude.json")
}

// EnsureTrusted marks each of dirs as trusted
// (projects.<abs-dir>.hasTrustDialogAccepted = true) in Claude Code's
// config. It's a no-op if the config file doesn't exist. When any
// directory needs updating, the file is backed up to
// "<path>.swarmforge.bak" first, then rewritten atomically (write to a
// temp file, rename over the original). A brand-new project entry is
// created with an empty allowedTools list; an existing entry's other
// fields are preserved untouched. Returns the number of directories newly
// marked trusted.
func EnsureTrusted(home string, dirs []string) (int, error) {
	path := Path(home)
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return 0, nil
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	var cfg map[string]interface{}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return 0, fmt.Errorf("parsing %s: %w", path, err)
	}

	projects, _ := cfg["projects"].(map[string]interface{})
	if projects == nil {
		projects = map[string]interface{}{}
	}

	changed := 0
	seen := map[string]bool{}
	for _, dir := range dirs {
		abs, err := filepath.Abs(dir)
		if err != nil {
			return changed, err
		}
		if seen[abs] {
			continue
		}
		seen[abs] = true

		entry, _ := projects[abs].(map[string]interface{})
		if entry != nil {
			if already, _ := entry["hasTrustDialogAccepted"].(bool); already {
				continue
			}
		} else {
			entry = map[string]interface{}{"allowedTools": []interface{}{}}
		}
		entry["hasTrustDialogAccepted"] = true
		projects[abs] = entry
		changed++
	}

	if changed == 0 {
		return 0, nil
	}
	cfg["projects"] = projects

	if err := copyFile(path, path+".swarmforge.bak"); err != nil {
		return 0, fmt.Errorf("backing up %s: %w", path, err)
	}

	out, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return 0, err
	}
	tmpPath := path + ".swarmforge.tmp"
	if err := os.WriteFile(tmpPath, out, 0o600); err != nil {
		return 0, err
	}
	if err := os.Rename(tmpPath, path); err != nil {
		return 0, err
	}
	return changed, nil
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0o600)
}
