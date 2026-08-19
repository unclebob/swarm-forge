package tui

import (
	"testing"

	tea "github.com/charmbracelet/bubbletea"
)

func TestKeyBytesRunes(t *testing.T) {
	got := keyBytes(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("a")})
	if string(got) != "a" {
		t.Fatalf("got %q", got)
	}
}

func TestKeyBytesMultiRune(t *testing.T) {
	// Some IME input methods can deliver multiple runes in one KeyMsg.
	got := keyBytes(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("好")})
	if string(got) != "好" {
		t.Fatalf("got %q", got)
	}
}

func TestKeyBytesControlKeysMatchTheirByteValue(t *testing.T) {
	cases := []struct {
		key  tea.KeyType
		want byte
	}{
		{tea.KeyCtrlC, 3},
		{tea.KeyEnter, 13},
		{tea.KeyTab, 9},
		{tea.KeyEsc, 27},
		{tea.KeyCtrlA, 1},
		{tea.KeyCtrlZ, 26},
	}
	for _, c := range cases {
		got := keyBytes(tea.KeyMsg{Type: c.key})
		if len(got) != 1 || got[0] != c.want {
			t.Fatalf("key %v: got %v, want [%d]", c.key, got, c.want)
		}
	}
}

func TestKeyBytesBackspace(t *testing.T) {
	got := keyBytes(tea.KeyMsg{Type: tea.KeyBackspace})
	if len(got) != 1 || got[0] != 127 {
		t.Fatalf("got %v", got)
	}
}

func TestKeyBytesSpace(t *testing.T) {
	got := keyBytes(tea.KeyMsg{Type: tea.KeySpace})
	if string(got) != " " {
		t.Fatalf("got %q", got)
	}
}

func TestKeyBytesArrowsAndEscapeSequences(t *testing.T) {
	cases := map[tea.KeyType]string{
		tea.KeyUp:     "\x1b[A",
		tea.KeyDown:   "\x1b[B",
		tea.KeyRight:  "\x1b[C",
		tea.KeyLeft:   "\x1b[D",
		tea.KeyHome:   "\x1b[H",
		tea.KeyEnd:    "\x1b[F",
		tea.KeyPgUp:   "\x1b[5~",
		tea.KeyPgDown: "\x1b[6~",
		tea.KeyDelete: "\x1b[3~",
		tea.KeyF1:     "\x1bOP",
	}
	for key, want := range cases {
		got := keyBytes(tea.KeyMsg{Type: key})
		if string(got) != want {
			t.Fatalf("key %v: got %q, want %q", key, got, want)
		}
	}
}

func TestKeyBytesUnmappedKeyReturnsNil(t *testing.T) {
	got := keyBytes(tea.KeyMsg{Type: tea.KeyCtrlShiftUp})
	if got != nil {
		t.Fatalf("expected nil for an unmapped key, got %v", got)
	}
}
