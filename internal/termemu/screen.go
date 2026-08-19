// Package termemu wraps hinshun/vt10x into a per-agent virtual screen: a
// goroutine feeds a PTY's raw output into it, and the TUI's render loop
// reads back a cell grid to draw. This is what lets the TUI show a full
// terminal-UI agent CLI (alt-screen switching, color, unicode) inside one
// of its own panes instead of shelling out to a real terminal emulator.
package termemu

import (
	"bufio"
	"io"
	"strings"

	"github.com/hinshun/vt10x"
)

// Screen is one agent's virtual terminal.
type Screen struct {
	vt vt10x.Terminal
}

// New creates a virtual terminal of the given size.
func New(cols, rows int) *Screen {
	return &Screen{vt: vt10x.New(vt10x.WithSize(cols, rows))}
}

// Feed runs the parse loop against r (typically a PTY master) until it
// returns EOF or another read error -- meant to run for the lifetime of
// the agent in its own goroutine.
func (s *Screen) Feed(r io.Reader) error {
	br := bufio.NewReader(r)
	for {
		if err := s.vt.Parse(br); err != nil {
			return err
		}
	}
}

// Resize changes the virtual terminal's size. The caller is also
// responsible for calling the PTY's own resize (see ptyagent.Agent.Resize)
// so the child process gets SIGWINCH and reflows to match.
func (s *Screen) Resize(cols, rows int) {
	s.vt.Resize(cols, rows)
}

// Size returns the virtual terminal's current dimensions.
func (s *Screen) Size() (cols, rows int) {
	s.vt.Lock()
	defer s.vt.Unlock()
	return s.vt.Size()
}

// CursorVisible reports whether the cursor should currently be drawn.
func (s *Screen) CursorVisible() bool {
	s.vt.Lock()
	defer s.vt.Unlock()
	return s.vt.CursorVisible()
}

// Render dumps the current grid as plain text, one row per line, blank
// cells rendered as spaces. This is the first-pass renderer used by tests
// and the PTY spike; a real lipgloss renderer (carrying per-cell
// fg/bg/attributes from Glyph) is a rendering-layer concern for the full
// TUI, not this package.
func (s *Screen) Render() string {
	s.vt.Lock()
	defer s.vt.Unlock()
	cols, rows := s.vt.Size()
	var b strings.Builder
	for y := 0; y < rows; y++ {
		for x := 0; x < cols; x++ {
			g := s.vt.Cell(x, y)
			if g.Char == 0 {
				b.WriteRune(' ')
			} else {
				b.WriteRune(g.Char)
			}
		}
		b.WriteRune('\n')
	}
	return b.String()
}
