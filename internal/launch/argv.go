// Package launch builds the argv and instruction file for each agent CLI
// backend. With agents spawned directly under a PTY (see internal/ptyagent)
// instead of typed into a tmux pane's shell, there's no shell in the loop
// any more: no quoting/escaping, just a plain []string argv per backend.
package launch

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// InstructionFile writes the per-role instruction file agents are told to
// read first (which in turn points them at the constitution and their own
// role prompt), returning its path.
func InstructionFile(stateDir, role string) (string, error) {
	dir := filepath.Join(stateDir, "prompts")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	content := fmt.Sprintf(
		"Read swarmforge/constitution.prompt, then read every file it refers to recursively, and obey all of those instructions.\n"+
			"Read swarmforge/roles/%s.prompt, then read every file it refers to recursively, and follow all of those instructions.\n",
		role,
	)
	path := filepath.Join(dir, role+".md")
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		return "", err
	}
	return path, nil
}

// wantsAutoApprove reports whether extraArgs opts into an agent's fully
// autonomous permission mode: "--yolo", "--always-approve", or an explicit
// "--permission-mode bypassPermissions" pair. Absent one of those, the
// default is the more conservative acceptEdits mode (auto-approves file
// edits, still stops on Bash/tool calls).
func wantsAutoApprove(extraArgs []string) bool {
	for i, a := range extraArgs {
		if a == "--yolo" || a == "--always-approve" {
			return true
		}
		if a == "--permission-mode" && i+1 < len(extraArgs) && extraArgs[i+1] == "bypassPermissions" {
			return true
		}
	}
	return false
}

func permissionMode(extraArgs []string) string {
	if wantsAutoApprove(extraArgs) {
		return "bypassPermissions"
	}
	return "acceptEdits"
}

// Spec is everything a backend's argv builder needs.
type Spec struct {
	Role         string
	DisplayName  string
	WorktreePath string
	ExtraArgs    []string
	PromptFile   string // from InstructionFile
	PromptText   string // PromptFile's content, read once by the caller
}

// BuildArgv returns the full argv (argv[0] is the backend's own command
// name) for a configured agent backend. agent must already be one of
// claude/codex/copilot/grok (see config.AllowedAgents).
func BuildArgv(agent string, s Spec) ([]string, error) {
	switch strings.ToLower(agent) {
	case "claude":
		return buildClaude(s), nil
	case "codex":
		return buildCodex(s), nil
	case "copilot":
		return buildCopilot(s), nil
	case "grok":
		return buildGrok(s), nil
	default:
		return nil, fmt.Errorf("unsupported agent backend %q", agent)
	}
}

func buildClaude(s Spec) []string {
	argv := []string{
		"claude",
		"--append-system-prompt-file", s.PromptFile,
		"--permission-mode", permissionMode(s.ExtraArgs),
		"-n", "SwarmForge " + s.DisplayName,
	}
	argv = append(argv, s.ExtraArgs...)
	return append(argv, s.PromptText)
}

func buildCodex(s Spec) []string {
	argv := []string{"codex", "-C", s.WorktreePath}
	argv = append(argv, s.ExtraArgs...)
	return append(argv, s.PromptText)
}

func buildCopilot(s Spec) []string {
	argv := []string{
		"copilot", "-C", s.WorktreePath,
		"--name", "SwarmForge " + s.DisplayName,
	}
	argv = append(argv, s.ExtraArgs...)
	return append(argv, "-i", s.PromptText)
}

func buildGrok(s Spec) []string {
	argv := []string{
		"grok", "--cwd", s.WorktreePath,
		"--permission-mode", permissionMode(s.ExtraArgs),
	}
	argv = append(argv, s.ExtraArgs...)
	return append(argv, "--rules", s.PromptText, "--verbatim", s.PromptText)
}
