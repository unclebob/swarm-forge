package orchestrator

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/TorratDev/swarm-forge/internal/config"
	"github.com/TorratDev/swarm-forge/internal/daemon"
	"github.com/TorratDev/swarm-forge/internal/launch"
	"github.com/TorratDev/swarm-forge/internal/ptyagent"
	"github.com/TorratDev/swarm-forge/internal/state"
	"github.com/TorratDev/swarm-forge/internal/termemu"
)

const (
	// DefaultCols/DefaultRows size each agent's PTY at launch; the TUI
	// resizes them once it knows the real pane dimensions.
	DefaultCols = 80
	DefaultRows = 24

	// PollInterval is how often the delivery daemon scans outboxes,
	// matching the original handoffd.bb's 1-second poll.
	PollInterval = 1 * time.Second
	// pollCheckInterval is how often the daemon loop checks for shutdown
	// while waiting out PollInterval, keeping shutdown responsive.
	pollCheckInterval = 100 * time.Millisecond

	// StartDelay staggers agent launches to avoid a thundering herd of
	// simultaneous API calls -- unrelated to tmux pane-creation races
	// (which no longer exist), but still useful without tmux.
	StartDelay = 1500 * time.Millisecond

	pidFileName = "swarmforge.pid"

	// CloseTimeout bounds how long Shutdown waits for an agent's process
	// group to exit after SIGTERM before escalating to SIGKILL.
	CloseTimeout = 5 * time.Second
)

// PIDFilePath is where the live "swarmforge up" process's own PID is
// recorded, so "swarmforge down" can find and signal it from another
// terminal.
func PIDFilePath(projectRoot string) string {
	return filepath.Join(projectRoot, ".swarmforge", pidFileName)
}

// SpawnFunc resolves an agent backend + launch spec into an executable
// name and argv[1:]. The default (see Launch) delegates to
// internal/launch's real per-backend argv builders; tests substitute a
// safe stand-in so they never have to actually exec a real agent CLI.
type SpawnFunc func(agent string, spec launch.Spec) (name string, args []string, err error)

func defaultSpawn(agent string, spec launch.Spec) (string, []string, error) {
	argv, err := launch.BuildArgv(agent, spec)
	if err != nil {
		return "", nil, err
	}
	return argv[0], argv[1:], nil
}

// notifier implements daemon.Notifier by writing directly into the
// recipient's PTY -- the direct-memory-access replacement for the
// original's 3-step tmux send-keys sequence. The artificial delays
// between those three tmux calls were compensating for tmux's own
// asynchronous key-injection queue; a raw PTY write has no such queue, so
// they're dropped here (see internal/termemu's PTY spike, which proved
// synchronous writes are seen reliably by a raw-mode child).
type notifier struct {
	mu     sync.RWMutex
	agents map[string]*ptyagent.Agent
}

func (n *notifier) Notify(role string) error {
	n.mu.RLock()
	agent, ok := n.agents[role]
	n.mu.RUnlock()
	if !ok {
		return fmt.Errorf("no live agent for role %q", role)
	}
	if _, err := agent.Write([]byte("You have new handoff mail. If idle, run ready_for_next.sh.\r\n")); err != nil {
		return err
	}
	return nil
}

// Run is one live "swarmforge up" swarm: every role's PTY-backed agent
// process, its virtual screen, and the handoff-delivery daemon goroutine.
type Run struct {
	ProjectRoot string
	Project     config.Project
	State       state.State

	mu      sync.RWMutex
	agents  map[string]*ptyagent.Agent
	screens map[string]*termemu.Screen

	notifier   *notifier
	cancel     context.CancelFunc
	daemonDone chan struct{}

	cleanupRole string
	exited      chan string // role names, as their agent processes exit

	shutdownOnce sync.Once
}

// Agent returns a role's live agent, if any.
func (r *Run) Agent(role string) (*ptyagent.Agent, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	a, ok := r.agents[role]
	return a, ok
}

// Screen returns a role's virtual screen, if any.
func (r *Run) Screen(role string) (*termemu.Screen, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	s, ok := r.screens[role]
	return s, ok
}

// Exited is signaled with a role's name each time that role's agent
// process exits. The cleanup role's exit is the signal a caller (the TUI,
// or a test) should treat as "the whole swarm should now shut down" --
// carried over unchanged from the original's index-0-role-exit-tears-
// down-the-swarm rule.
func (r *Run) Exited() <-chan string {
	return r.exited
}

// CleanupRole is the role whose exit should trigger full swarm teardown.
func (r *Run) CleanupRole() string {
	return r.cleanupRole
}

// Launch starts every configured role's agent process under its own PTY
// (staggered by startDelay), starts the handoff-delivery daemon as a
// goroutine, and writes a PID file recording this process, so
// "swarmforge down" can find and signal it from another terminal.
func Launch(projectRoot string, proj config.Project, st state.State, startDelay time.Duration, spawn SpawnFunc) (*Run, error) {
	if spawn == nil {
		spawn = defaultSpawn
	}
	cleanupRole, ok := proj.CleanupRole()
	if !ok {
		return nil, fmt.Errorf("project has no roles configured")
	}

	pidPath := PIDFilePath(projectRoot)
	if err := os.MkdirAll(filepath.Dir(pidPath), 0o755); err != nil {
		return nil, err
	}
	if err := os.WriteFile(pidPath, []byte(strconv.Itoa(os.Getpid())+"\n"), 0o644); err != nil {
		return nil, err
	}

	run := &Run{
		ProjectRoot: projectRoot,
		Project:     proj,
		State:       st,
		agents:      map[string]*ptyagent.Agent{},
		screens:     map[string]*termemu.Screen{},
		cleanupRole: cleanupRole.Name,
		exited:      make(chan string, len(proj.Roles)),
	}
	run.notifier = &notifier{agents: run.agents}

	stateDir := filepath.Join(projectRoot, ".swarmforge")
	binDir := filepath.Join(stateDir, "bin")

	for i, r := range proj.Roles {
		if i > 0 && startDelay > 0 {
			time.Sleep(startDelay)
		}
		role, ok := st.RoleByName(r.Name)
		if !ok {
			run.shutdownLaunched()
			return nil, fmt.Errorf("role %q missing from state", r.Name)
		}

		promptFile, err := launch.InstructionFile(stateDir, r.Name)
		if err != nil {
			run.shutdownLaunched()
			return nil, err
		}
		promptText, err := os.ReadFile(promptFile)
		if err != nil {
			run.shutdownLaunched()
			return nil, err
		}

		name, args, err := spawn(r.Agent, launch.Spec{
			Role: r.Name, DisplayName: role.DisplayName, WorktreePath: role.WorktreePath,
			ExtraArgs: r.ExtraArgs, PromptFile: promptFile, PromptText: string(promptText),
		})
		if err != nil {
			run.shutdownLaunched()
			return nil, fmt.Errorf("building launch command for role %q: %w", r.Name, err)
		}

		env := append(os.Environ(),
			"SWARMFORGE_ROLE="+r.Name,
			"PATH="+binDir+string(os.PathListSeparator)+os.Getenv("PATH"),
		)
		agent, err := ptyagent.Start(name, args, role.WorktreePath, env, DefaultCols, DefaultRows)
		if err != nil {
			run.shutdownLaunched()
			return nil, fmt.Errorf("launching role %q: %w", r.Name, err)
		}

		screen := termemu.New(DefaultCols, DefaultRows)
		go screen.Feed(agent.PTY)

		roleName := r.Name
		go func() {
			_ = agent.Wait()
			run.exited <- roleName
		}()

		run.mu.Lock()
		run.agents[roleName] = agent
		run.screens[roleName] = screen
		run.mu.Unlock()
	}

	ctx, cancel := context.WithCancel(context.Background())
	run.cancel = cancel
	run.daemonDone = make(chan struct{})
	go run.daemonLoop(ctx)

	return run, nil
}

// shutdownLaunched closes whatever agents were already started, for
// cleanup when Launch fails partway through.
func (r *Run) shutdownLaunched() {
	r.mu.RLock()
	agents := make([]*ptyagent.Agent, 0, len(r.agents))
	for _, a := range r.agents {
		agents = append(agents, a)
	}
	r.mu.RUnlock()
	for _, a := range agents {
		_ = a.Close(CloseTimeout)
	}
	_ = os.Remove(PIDFilePath(r.ProjectRoot))
}

func (r *Run) daemonLoop(ctx context.Context) {
	defer close(r.daemonDone)
	for {
		daemon.PollOnce(r.State, r.notifier, time.Now())
		if waitOrDone(ctx, PollInterval, pollCheckInterval) {
			return
		}
	}
}

// waitOrDone sleeps for total, checking ctx.Done() every check interval,
// and returns true if ctx was canceled before total elapsed.
func waitOrDone(ctx context.Context, total, check time.Duration) bool {
	deadline := time.Now().Add(total)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return true
		case <-time.After(check):
		}
	}
	return false
}

// Shutdown stops the delivery daemon and terminates every agent's entire
// process group, then removes the PID file. It's the single code path
// every shutdown trigger funnels through -- "swarmforge down" (via a
// SIGTERM this process's signal handler routes here), the TUI's leader+q
// quit, the cleanup role's own process exiting, and a top-level error-path
// safety net can all call it; a sync.Once makes that safe to do from more
// than one of them without double-closing anything.
func (r *Run) Shutdown() {
	r.shutdownOnce.Do(func() {
		if r.cancel != nil {
			r.cancel()
			<-r.daemonDone
		}
		r.mu.RLock()
		agents := make([]*ptyagent.Agent, 0, len(r.agents))
		for _, a := range r.agents {
			agents = append(agents, a)
		}
		r.mu.RUnlock()

		var wg sync.WaitGroup
		for _, a := range agents {
			wg.Add(1)
			go func(a *ptyagent.Agent) {
				defer wg.Done()
				_ = a.Close(CloseTimeout)
			}(a)
		}
		wg.Wait()

		_ = os.Remove(PIDFilePath(r.ProjectRoot))
	})
}

// ReadPID reads the PID recorded by a live "swarmforge up" process.
func ReadPID(projectRoot string) (int, error) {
	b, err := os.ReadFile(PIDFilePath(projectRoot))
	if err != nil {
		return 0, err
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil {
		return 0, fmt.Errorf("malformed PID file: %w", err)
	}
	return pid, nil
}
