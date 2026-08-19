package cli

import (
	"fmt"
	"path/filepath"

	"github.com/TorratDev/swarm-forge/internal/handoff"
	"github.com/TorratDev/swarm-forge/internal/state"
)

func currentRole(env Env) (string, state.Role, state.State, int, bool) {
	role := env.Getenv("SWARMFORGE_ROLE")
	if role == "" {
		fmt.Fprintln(env.Stderr, "Set SWARMFORGE_ROLE.")
		return "", state.Role{}, state.State{}, 1, false
	}
	projectRoot, err := state.FindProjectRoot(env.Cwd)
	if err != nil {
		fmt.Fprintln(env.Stderr, err)
		return "", state.Role{}, state.State{}, 1, false
	}
	st, err := state.Load(projectRoot)
	if err != nil {
		fmt.Fprintln(env.Stderr, "Could not load project state:", err)
		return "", state.Role{}, state.State{}, 1, false
	}
	r, ok := st.RoleByName(role)
	if !ok {
		fmt.Fprintln(env.Stderr, "Unknown role:", role)
		return "", state.Role{}, state.State{}, 1, false
	}
	return role, r, st, 0, true
}

// RunReady accepts the current role's next task or batch, per its
// configured receive mode. Go equivalent of ready_for_next.bb (+ its
// task/batch variants).
func RunReady(env Env) int {
	_, role, _, code, ok := currentRole(env)
	if !ok {
		return code
	}
	inboxDir := filepath.Join(env.Cwd, ".swarmforge", "handoffs", "inbox")
	return handoff.ReadyForNext(inboxDir, role.ReceiveMode, env.Now(), env.Stdout, env.Stderr)
}

// RunDone completes the current role's in-process task or batch, per its
// configured receive mode, then immediately reports the next one. Go
// equivalent of done_with_current.bb (+ its task/batch variants).
func RunDone(env Env) int {
	_, role, _, code, ok := currentRole(env)
	if !ok {
		return code
	}
	inboxDir := filepath.Join(env.Cwd, ".swarmforge", "handoffs", "inbox")
	return handoff.DoneWithCurrent(inboxDir, role.ReceiveMode, env.Now(), env.Stdout, env.Stderr)
}
