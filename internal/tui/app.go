// Package tui implements SwarmForge's multi-pane terminal UI: one pane
// per configured role, each showing that role's agent CLI running under
// its own PTY (see internal/ptyagent, internal/termemu). This replaces
// the original tmux + native-terminal-window model with a single
// in-process view. There is no tmux prefix key any more, so a leader key
// (Ctrl-A) plays the same role: it puts the next keystroke into "control
// mode" (switch pane, quit); every other keystroke, including Ctrl-C,
// passes straight through to the focused pane's agent.
package tui

import (
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"github.com/TorratDev/swarm-forge/internal/orchestrator"
)

// tickInterval is the render refresh rate: how often the TUI re-reads the
// focused pane's virtual screen and redraws.
const tickInterval = 50 * time.Millisecond

// LeaderKey is the control-mode prefix key. Ctrl-B is avoided (many
// agent CLIs bind it themselves) in favor of Ctrl-A, mirroring GNU
// screen's default -- a choice unlikely to collide with what the agent
// CLIs running inside each pane use themselves.
const LeaderKey = tea.KeyCtrlA

type frameMsg struct{}
type exitedMsg string

// Model is the bubbletea model for the whole swarm view.
type Model struct {
	run   *orchestrator.Run
	roles []string
	focus int

	width, height int
	leaderMode    bool
	quitting      bool
	status        string
}

// New builds the TUI model for a launched swarm. roles is the pane order
// (normally config.Project's role order, so pane 1 is the cleanup role).
func New(run *orchestrator.Run, roles []string) Model {
	return Model{run: run, roles: roles}
}

func (m Model) Init() tea.Cmd {
	return tea.Batch(tick(), waitExited(m.run))
}

func tick() tea.Cmd {
	return tea.Tick(tickInterval, func(time.Time) tea.Msg { return frameMsg{} })
}

func waitExited(run *orchestrator.Run) tea.Cmd {
	return func() tea.Msg {
		role, ok := <-run.Exited()
		if !ok {
			return nil
		}
		return exitedMsg(role)
	}
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case frameMsg:
		return m, tick()

	case exitedMsg:
		role := string(msg)
		if role == m.run.CleanupRole() {
			m.run.Shutdown()
			m.quitting = true
			return m, tea.Quit
		}
		m.status = fmt.Sprintf("role %q exited", role)
		return m, waitExited(m.run)

	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
		paneHeight := msg.Height - 1
		if paneHeight < 1 {
			paneHeight = 1
		}
		for _, role := range m.roles {
			if agent, ok := m.run.Agent(role); ok {
				_ = agent.Resize(msg.Width, paneHeight)
			}
			if screen, ok := m.run.Screen(role); ok {
				screen.Resize(msg.Width, paneHeight)
			}
		}
		return m, nil

	case tea.KeyMsg:
		return m.handleKey(msg)
	}
	return m, nil
}

func (m Model) handleKey(msg tea.KeyMsg) (tea.Model, tea.Cmd) {
	if m.leaderMode {
		m.leaderMode = false
		return m.handleLeaderKey(msg)
	}
	if msg.Type == LeaderKey {
		m.leaderMode = true
		m.status = "leader"
		return m, nil
	}
	if agent, ok := m.currentAgent(); ok {
		if b := keyBytes(msg); b != nil {
			_, _ = agent.Write(b)
		}
	}
	return m, nil
}

func (m Model) handleLeaderKey(msg tea.KeyMsg) (tea.Model, tea.Cmd) {
	m.status = ""
	switch msg.String() {
	case "q":
		m.run.Shutdown()
		m.quitting = true
		return m, tea.Quit
	default:
		if n, err := strconv.Atoi(msg.String()); err == nil && n >= 1 && n <= len(m.roles) {
			m.focus = n - 1
		}
	}
	return m, nil
}

func (m Model) currentAgent() (io.Writer, bool) {
	if m.focus < 0 || m.focus >= len(m.roles) {
		return nil, false
	}
	return m.run.Agent(m.roles[m.focus])
}

// Quitting reports whether the model has decided to exit -- exposed for
// tests, which can't observe tea.Quit's effect on a real tea.Program
// without running one.
func (m Model) Quitting() bool { return m.quitting }

// Focus reports the index (into roles) of the currently focused pane.
func (m Model) Focus() int { return m.focus }

// LeaderMode reports whether the next keystroke will be interpreted as a
// leader-key command rather than forwarded to the focused pane.
func (m Model) LeaderMode() bool { return m.leaderMode }

var statusBarStyle = lipgloss.NewStyle().
	Bold(true).
	Foreground(lipgloss.Color("0")).
	Background(lipgloss.Color("4"))

func (m Model) View() string {
	if m.quitting {
		return ""
	}
	var bar strings.Builder
	for i, role := range m.roles {
		label := fmt.Sprintf(" %d:%s ", i+1, role)
		if i == m.focus {
			bar.WriteString(statusBarStyle.Bold(true).Underline(true).Render(label))
		} else {
			bar.WriteString(statusBarStyle.Render(label))
		}
	}
	if m.leaderMode {
		bar.WriteString(statusBarStyle.Render(" [leader: 1-9 switch pane, q quit] "))
	} else if m.status != "" {
		bar.WriteString(statusBarStyle.Render(" " + m.status + " "))
	}

	body := ""
	if m.focus >= 0 && m.focus < len(m.roles) {
		if screen, ok := m.run.Screen(m.roles[m.focus]); ok {
			body = screen.Render()
		}
	}
	return bar.String() + "\n" + body
}
