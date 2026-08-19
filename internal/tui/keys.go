package tui

import tea "github.com/charmbracelet/bubbletea"

// keyBytes translates a bubbletea key event into the raw bytes a terminal
// would have sent for it, for forwarding into a focused agent's PTY.
//
// This works for the whole 0-31 control range and DEL (127) essentially
// for free: bubbletea's KeyType numeric values are themselves defined to
// equal the C0 control-code byte for that key (e.g. KeyCtrlC == 3,
// KeyEnter == 13, KeyTab == 9, KeyBackspace == 127) -- see bubbletea's
// key.go. Everything else (arrows, Home/End, function keys, ...) needs an
// explicit ANSI escape sequence, and a few (Space, Runes) aren't control
// codes at all.
func keyBytes(msg tea.KeyMsg) []byte {
	if msg.Type == tea.KeyRunes {
		return []byte(string(msg.Runes))
	}
	if msg.Type == tea.KeySpace {
		return []byte(" ")
	}
	if msg.Type >= 0 && msg.Type <= 31 {
		return []byte{byte(msg.Type)}
	}
	if msg.Type == tea.KeyBackspace {
		return []byte{127}
	}
	if seq, ok := escapeSequences[msg.Type]; ok {
		return []byte(seq)
	}
	return nil
}

// escapeSequences covers the common xterm CSI/SS3 sequences for keys that
// aren't single control bytes. Less common combos (ctrl+shift+arrow, most
// function keys beyond F4, ...) are intentionally left unmapped for now --
// forwarding nothing is safer than forwarding a guessed-wrong sequence.
var escapeSequences = map[tea.KeyType]string{
	tea.KeyUp:       "\x1b[A",
	tea.KeyDown:     "\x1b[B",
	tea.KeyRight:    "\x1b[C",
	tea.KeyLeft:     "\x1b[D",
	tea.KeyHome:     "\x1b[H",
	tea.KeyEnd:      "\x1b[F",
	tea.KeyPgUp:     "\x1b[5~",
	tea.KeyPgDown:   "\x1b[6~",
	tea.KeyDelete:   "\x1b[3~",
	tea.KeyInsert:   "\x1b[2~",
	tea.KeyShiftTab: "\x1b[Z",
	tea.KeyF1:       "\x1bOP",
	tea.KeyF2:       "\x1bOQ",
	tea.KeyF3:       "\x1bOR",
	tea.KeyF4:       "\x1bOS",
}
