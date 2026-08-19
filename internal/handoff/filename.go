package handoff

import (
	"fmt"
	"strings"
	"time"
)

// IDTimestamp formats now as "YYYYMMDDTHHMMSSZ" (UTC), the timestamp
// component used in both filenames and the "id" header.
func IDTimestamp(now time.Time) string {
	return now.UTC().Format("20060102T150405Z")
}

// CreatedAt formats now as an ISO-8601 UTC instant, e.g.
// "2026-06-15T14:05:31Z", used for created_at/enqueued_at/dequeued_at/
// completed_at header values.
func CreatedAt(now time.Time) string {
	return now.UTC().Format(time.RFC3339)
}

// Filename builds "<priority>_<timestampID>_<sequence>_from_<sender>_to_<recipients>.handoff".
// Lexicographic sort of these filenames is the queue's ordering guarantee:
// lower priority first, then earlier timestamp, then lower sequence.
func Filename(priority, timestampID, sequence, sender string, recipients []string) string {
	return fmt.Sprintf("%s_%s_%s_from_%s_to_%s.handoff",
		priority, timestampID, sequence, sender, strings.Join(recipients, "_"))
}

// ID builds the "id" header value: "<timestampID>_<sequence>_from_<sender>".
func ID(timestampID, sequence, sender string) string {
	return fmt.Sprintf("%s_%s_from_%s", timestampID, sequence, sender)
}
