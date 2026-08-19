package handoff

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// NextSequence returns the next monotonic 6-digit sequence number (as used
// in handoff filenames) for the outbox rooted at handoffsDir, guarded by a
// lock directory: os.Mkdir is atomic on POSIX filesystems, giving mutual
// exclusion without a third-party file-locking dependency.
func NextSequence(handoffsDir string) (string, error) {
	if err := os.MkdirAll(handoffsDir, 0o755); err != nil {
		return "", err
	}
	seqFile := filepath.Join(handoffsDir, "sequence")
	lockDir := filepath.Join(handoffsDir, "sequence.lock")

	for {
		err := os.Mkdir(lockDir, 0o755)
		if err == nil {
			break
		}
		if !os.IsExist(err) {
			return "", err
		}
		time.Sleep(50 * time.Millisecond)
	}
	defer os.Remove(lockDir)

	last := 0
	if b, err := os.ReadFile(seqFile); err == nil {
		if n, err := strconv.Atoi(strings.TrimSpace(string(b))); err == nil {
			last = n
		}
	}
	formatted := fmt.Sprintf("%06d", last+1)
	if err := os.WriteFile(seqFile, []byte(formatted+"\n"), 0o644); err != nil {
		return "", err
	}
	return formatted, nil
}
