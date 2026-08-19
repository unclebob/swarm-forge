// Package handoff implements SwarmForge's file-based handoff protocol:
// parsing and rendering ".handoff" message files, generating their
// filenames, validating operator-authored drafts, and moving them through
// the inbox/outbox queue state machine. See swarmforge/handoff-protocol.md
// for the on-disk format this package implements.
package handoff

import (
	"sort"
	"strings"
)

// PreferredHeaderOrder is the canonical header ordering used whenever a
// message already on disk is rewritten (delivery, dequeue, completion).
// Any header not in this list is appended after, sorted alphabetically.
// This intentionally omits "task": the original implementation's
// render-message function never special-cased it, so a git_handoff's
// "task" header always lands in the alphabetical tail.
var PreferredHeaderOrder = []string{
	"id", "from", "to", "recipient", "priority", "type",
	"role", "commit", "message", "created_at", "enqueued_at",
	"dequeued_at", "completed_at",
}

// Message is a parsed handoff file: a header block followed by a blank
// line and an opaque body.
type Message struct {
	Headers map[string]string
	Body    string
}

// Parse splits header block from body on the first blank line ("\n\n").
func Parse(content string) Message {
	header, body, found := strings.Cut(content, "\n\n")
	if !found {
		header, body = content, ""
	}
	headers := map[string]string{}
	for _, line := range strings.Split(header, "\n") {
		if line == "" {
			continue
		}
		k, v, ok := strings.Cut(line, ": ")
		if !ok {
			continue
		}
		headers[k] = v
	}
	return Message{Headers: headers, Body: body}
}

// Render serializes the message using PreferredHeaderOrder followed by any
// remaining headers sorted alphabetically, a blank line, then the body.
// Only headers with a non-empty value are written.
func (m Message) Render() string {
	seen := make(map[string]bool, len(PreferredHeaderOrder))
	var lines []string
	for _, k := range PreferredHeaderOrder {
		seen[k] = true
		if v := m.Headers[k]; v != "" {
			lines = append(lines, k+": "+v)
		}
	}
	var remaining []string
	for k, v := range m.Headers {
		if !seen[k] && v != "" {
			remaining = append(remaining, k)
		}
	}
	sort.Strings(remaining)
	for _, k := range remaining {
		lines = append(lines, k+": "+m.Headers[k])
	}
	return strings.Join(lines, "\n") + "\n\n" + m.Body
}

// WithHeader returns a copy of the message with field set to value.
func (m Message) WithHeader(field, value string) Message {
	headers := make(map[string]string, len(m.Headers)+1)
	for k, v := range m.Headers {
		headers[k] = v
	}
	headers[field] = value
	return Message{Headers: headers, Body: m.Body}
}
