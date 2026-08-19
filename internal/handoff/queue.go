package handoff

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// ListHandoffFiles returns the ".handoff" regular files directly under dir,
// sorted by filename -- the sort order the priority/timestamp/sequence
// filename scheme is designed to make into enqueue order.
func ListHandoffFiles(dir string) []string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var files []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".handoff") {
			files = append(files, filepath.Join(dir, e.Name()))
		}
	}
	sort.Strings(files)
	return files
}

// ListBatchDirs returns the "batch_*" directories directly under dir,
// sorted by name.
func ListBatchDirs(dir string) []string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var dirs []string
	for _, e := range entries {
		if e.IsDir() && strings.HasPrefix(e.Name(), "batch_") {
			dirs = append(dirs, filepath.Join(dir, e.Name()))
		}
	}
	sort.Strings(dirs)
	return dirs
}

// HeaderField reads a single header's value from a handoff file, scanning
// only up to the first blank line (the header block).
func HeaderField(path, field string) (string, bool) {
	content, err := os.ReadFile(path)
	if err != nil {
		return "", false
	}
	prefix := field + ": "
	for _, line := range strings.Split(string(content), "\n") {
		if strings.TrimSpace(line) == "" {
			break
		}
		if strings.HasPrefix(line, prefix) {
			return strings.TrimPrefix(line, prefix), true
		}
	}
	return "", false
}

func headerValue(path, field, def string) string {
	if v, ok := HeaderField(path, field); ok {
		return v
	}
	return def
}

// BodyOf reads a handoff file's payload (everything after the first blank
// line).
func BodyOf(path string) string {
	content, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	_, body, found := strings.Cut(string(content), "\n\n")
	if !found {
		return ""
	}
	return body
}

// SetHeader rewrites a single header's value in place, inserting it just
// before the header block's terminating blank line if not already present,
// or replacing the existing "<field>: " line if it is. The file is
// rewritten atomically via a temp file in the same directory.
func SetHeader(path, field, value string) error {
	content, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	lines := strings.Split(string(content), "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	prefix := field + ": "
	newLine := prefix + value

	var out []string
	inserted, replaced := false, false
	for _, line := range lines {
		switch {
		case !inserted && strings.TrimSpace(line) == "":
			if !replaced {
				out = append(out, newLine)
			}
			out = append(out, line)
			inserted = true
		case !inserted && strings.HasPrefix(line, prefix):
			out = append(out, newLine)
			replaced = true
		default:
			out = append(out, line)
		}
	}
	if !inserted && !replaced {
		out = append(out, newLine)
	}

	tmp, err := os.CreateTemp(filepath.Dir(path), ".headers.")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	if _, err := tmp.WriteString(strings.Join(out, "\n") + "\n"); err != nil {
		tmp.Close()
		os.Remove(tmpPath)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpPath)
		return err
	}
	return os.Rename(tmpPath, path)
}

// PrintTask writes a single task's report in the format agent prompts
// parse: TASK/FROM/TYPE/PRIORITY[/TASK_NAME]/PAYLOAD.
func PrintTask(w io.Writer, path string) {
	fmt.Fprintln(w, "TASK:", path)
	fmt.Fprintln(w, "FROM:", headerValue(path, "from", "unknown"))
	fmt.Fprintln(w, "TYPE:", headerValue(path, "type", "unknown"))
	fmt.Fprintln(w, "PRIORITY:", headerValue(path, "priority", "50"))
	if taskName, ok := HeaderField(path, "task"); ok {
		fmt.Fprintln(w, "TASK_NAME:", taskName)
	}
	fmt.Fprintln(w, "PAYLOAD:")
	fmt.Fprint(w, BodyOf(path))
}

// PrintBatch writes a batch's report: BATCH/COUNT/PRIORITY followed by each
// item's report, in delivered (filename-sorted) order.
func PrintBatch(w io.Writer, batchDir string) error {
	files := ListHandoffFiles(batchDir)
	if len(files) == 0 {
		return fmt.Errorf("AMBIGUOUS_TASK_STATE: batch contains no tasks: %s", batchDir)
	}
	fmt.Fprintln(w, "BATCH:", batchDir)
	fmt.Fprintln(w, "COUNT:", len(files))
	fmt.Fprintln(w, "PRIORITY:", headerValue(files[0], "priority", "50"))
	for i, f := range files {
		fmt.Fprintln(w)
		fmt.Fprintln(w, "BATCH_ITEM:", i+1)
		PrintTask(w, f)
	}
	return nil
}

func ensureDirs(dirs ...string) error {
	for _, d := range dirs {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	return nil
}

func fail(stderr io.Writer, code int, lines ...string) int {
	for _, l := range lines {
		fmt.Fprintln(stderr, l)
	}
	return code
}

func listedLines(prefix string, paths []string) []string {
	lines := make([]string, len(paths))
	for i, p := range paths {
		lines[i] = prefix + p
	}
	return lines
}

// ReadyForNextTask accepts the next single task for a role: if a task is
// already in process it is re-reported (idempotent); otherwise the oldest
// file in inbox/new is moved into inbox/in_process, stamped dequeued_at,
// and reported. Returns the process exit code that should be used.
func ReadyForNextTask(inboxDir string, now time.Time, stdout, stderr io.Writer) int {
	newDir := filepath.Join(inboxDir, "new")
	inProcessDir := filepath.Join(inboxDir, "in_process")
	completedDir := filepath.Join(inboxDir, "completed")
	if err := ensureDirs(newDir, inProcessDir, completedDir); err != nil {
		return fail(stderr, 2, err.Error())
	}

	inProcessBatches := ListBatchDirs(inProcessDir)
	inProcessFiles := ListHandoffFiles(inProcessDir)

	if len(inProcessBatches) > 0 {
		lines := append([]string{"TASK_IN_PROCESS_IS_BATCH: this role receives batches; use the batch commands."}, listedLines("- ", inProcessBatches)...)
		return fail(stderr, 2, lines...)
	}
	if len(inProcessFiles) > 1 {
		lines := append([]string{"AMBIGUOUS_TASK_STATE: multiple tasks are already in process."}, listedLines("- ", inProcessFiles)...)
		return fail(stderr, 2, lines...)
	}
	if len(inProcessFiles) == 1 {
		PrintTask(stdout, inProcessFiles[0])
		return 0
	}

	newFiles := ListHandoffFiles(newDir)
	if len(newFiles) == 0 {
		fmt.Fprintln(stdout, "NO_TASK")
		return 0
	}
	sourceFile := newFiles[0]
	targetFile := filepath.Join(inProcessDir, filepath.Base(sourceFile))
	if _, err := os.Stat(targetFile); err == nil {
		return fail(stderr, 2, "AMBIGUOUS_TASK_STATE: target in-process file already exists: "+targetFile)
	}
	if err := os.Rename(sourceFile, targetFile); err != nil {
		return fail(stderr, 2, err.Error())
	}
	if err := SetHeader(targetFile, "dequeued_at", CreatedAt(now)); err != nil {
		return fail(stderr, 2, err.Error())
	}
	PrintTask(stdout, targetFile)
	return 0
}

// ReadyForNextBatch accepts the next batch of equal-priority tasks for a
// role: if a batch is already in process it is re-reported; otherwise every
// inbox/new file sharing the oldest file's priority is grouped into a new
// inbox/in_process/batch_<ts>_<suffix>/ directory, each stamped
// dequeued_at, and the batch is reported.
func ReadyForNextBatch(inboxDir string, now time.Time, stdout, stderr io.Writer) int {
	newDir := filepath.Join(inboxDir, "new")
	inProcessDir := filepath.Join(inboxDir, "in_process")
	completedDir := filepath.Join(inboxDir, "completed")
	if err := ensureDirs(newDir, inProcessDir, completedDir); err != nil {
		return fail(stderr, 2, err.Error())
	}

	inProcessBatches := ListBatchDirs(inProcessDir)
	inProcessFiles := ListHandoffFiles(inProcessDir)

	if len(inProcessFiles) > 0 {
		lines := append([]string{"TASK_IN_PROCESS_IS_SINGLE: this role receives batches; a single task is stuck in process."}, listedLines("- ", inProcessFiles)...)
		return fail(stderr, 2, lines...)
	}
	if len(inProcessBatches) > 1 {
		lines := append([]string{"AMBIGUOUS_TASK_STATE: multiple batches are already in process."}, listedLines("- ", inProcessBatches)...)
		return fail(stderr, 2, lines...)
	}
	if len(inProcessBatches) == 1 {
		if err := PrintBatch(stdout, inProcessBatches[0]); err != nil {
			return fail(stderr, 2, err.Error())
		}
		return 0
	}

	newFiles := ListHandoffFiles(newDir)
	if len(newFiles) == 0 {
		fmt.Fprintln(stdout, "NO_TASK")
		return 0
	}
	batchPriority := headerValue(newFiles[0], "priority", "50")
	batchDir := newBatchDir(inProcessDir, now)
	var selected []string
	for _, f := range newFiles {
		if headerValue(f, "priority", "50") == batchPriority {
			selected = append(selected, f)
		}
	}
	if len(selected) == 0 {
		return fail(stderr, 2, fmt.Sprintf("AMBIGUOUS_TASK_STATE: no tasks selected for batch priority %s.", batchPriority))
	}
	if err := os.MkdirAll(batchDir, 0o755); err != nil {
		return fail(stderr, 2, err.Error())
	}
	for _, sourceFile := range selected {
		targetFile := filepath.Join(batchDir, filepath.Base(sourceFile))
		if _, err := os.Stat(targetFile); err == nil {
			return fail(stderr, 2, "AMBIGUOUS_TASK_STATE: target batch file already exists: "+targetFile)
		}
		if err := os.Rename(sourceFile, targetFile); err != nil {
			return fail(stderr, 2, err.Error())
		}
		if err := SetHeader(targetFile, "dequeued_at", CreatedAt(now)); err != nil {
			return fail(stderr, 2, err.Error())
		}
	}
	if err := PrintBatch(stdout, batchDir); err != nil {
		return fail(stderr, 2, err.Error())
	}
	return 0
}

func newBatchDir(inProcessDir string, now time.Time) string {
	for suffix := 1; ; suffix++ {
		dir := filepath.Join(inProcessDir, fmt.Sprintf("batch_%s_%06d", IDTimestamp(now), suffix))
		if _, err := os.Stat(dir); os.IsNotExist(err) {
			return dir
		}
	}
}

// DoneWithCurrentTask completes the single in-process task (stamping
// completed_at and moving it to inbox/completed), then immediately calls
// ReadyForNextTask so completion always pulls the next item in one step.
func DoneWithCurrentTask(inboxDir string, now time.Time, stdout, stderr io.Writer) int {
	inProcessDir := filepath.Join(inboxDir, "in_process")
	completedDir := filepath.Join(inboxDir, "completed")
	if err := ensureDirs(inProcessDir, completedDir); err != nil {
		return fail(stderr, 2, err.Error())
	}

	inProcessBatches := ListBatchDirs(inProcessDir)
	inProcessFiles := ListHandoffFiles(inProcessDir)

	if len(inProcessBatches) > 0 {
		lines := append([]string{"CURRENT_WORK_IS_BATCH: use the batch commands to complete it."}, listedLines("- ", inProcessBatches)...)
		return fail(stderr, 2, lines...)
	}
	if len(inProcessFiles) == 0 {
		return fail(stderr, 1, "NO_CURRENT_TASK")
	}
	if len(inProcessFiles) > 1 {
		lines := append([]string{"AMBIGUOUS_TASK_STATE: multiple tasks are in process."}, listedLines("- ", inProcessFiles)...)
		return fail(stderr, 2, lines...)
	}

	sourceFile := inProcessFiles[0]
	targetFile := filepath.Join(completedDir, filepath.Base(sourceFile))
	if err := SetHeader(sourceFile, "completed_at", CreatedAt(now)); err != nil {
		return fail(stderr, 2, err.Error())
	}
	if _, err := os.Stat(targetFile); err == nil {
		return fail(stderr, 2, "AMBIGUOUS_TASK_STATE: completed file already exists: "+targetFile)
	}
	if err := os.Rename(sourceFile, targetFile); err != nil {
		return fail(stderr, 2, err.Error())
	}
	fmt.Fprintln(stdout, "COMPLETED:", targetFile)
	return ReadyForNextTask(inboxDir, now, stdout, stderr)
}

// DoneWithCurrentBatch completes every file in the single in-process batch
// (stamping completed_at, moving the whole directory to inbox/completed),
// then immediately calls ReadyForNextBatch.
func DoneWithCurrentBatch(inboxDir string, now time.Time, stdout, stderr io.Writer) int {
	inProcessDir := filepath.Join(inboxDir, "in_process")
	completedDir := filepath.Join(inboxDir, "completed")
	if err := ensureDirs(inProcessDir, completedDir); err != nil {
		return fail(stderr, 2, err.Error())
	}

	inProcessBatches := ListBatchDirs(inProcessDir)
	inProcessFiles := ListHandoffFiles(inProcessDir)

	if len(inProcessFiles) > 0 {
		lines := append([]string{"CURRENT_WORK_IS_SINGLE_TASK: use the task commands to complete it."}, listedLines("- ", inProcessFiles)...)
		return fail(stderr, 2, lines...)
	}
	if len(inProcessBatches) == 0 {
		return fail(stderr, 1, "NO_CURRENT_BATCH")
	}
	if len(inProcessBatches) > 1 {
		lines := append([]string{"AMBIGUOUS_TASK_STATE: multiple batches are in process."}, listedLines("- ", inProcessBatches)...)
		return fail(stderr, 2, lines...)
	}

	sourceDir := inProcessBatches[0]
	batchFiles := ListHandoffFiles(sourceDir)
	targetDir := filepath.Join(completedDir, filepath.Base(sourceDir))
	completedAt := CreatedAt(now)

	if len(batchFiles) == 0 {
		return fail(stderr, 2, "AMBIGUOUS_TASK_STATE: batch contains no tasks: "+sourceDir)
	}
	if _, err := os.Stat(targetDir); err == nil {
		return fail(stderr, 2, "AMBIGUOUS_TASK_STATE: completed batch already exists: "+targetDir)
	}
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		return fail(stderr, 2, err.Error())
	}
	for _, sourceFile := range batchFiles {
		if err := SetHeader(sourceFile, "completed_at", completedAt); err != nil {
			return fail(stderr, 2, err.Error())
		}
		targetFile := filepath.Join(targetDir, filepath.Base(sourceFile))
		if _, err := os.Stat(targetFile); err == nil {
			return fail(stderr, 2, "AMBIGUOUS_TASK_STATE: completed batch file already exists: "+targetFile)
		}
		if err := os.Rename(sourceFile, targetFile); err != nil {
			return fail(stderr, 2, err.Error())
		}
		fmt.Fprintln(stdout, "COMPLETED:", targetFile)
	}
	if err := os.Remove(sourceDir); err != nil {
		return fail(stderr, 2, err.Error())
	}
	fmt.Fprintln(stdout, "COMPLETED_BATCH:", targetDir)
	return ReadyForNextBatch(inboxDir, now, stdout, stderr)
}

// ReadyForNext dispatches to ReadyForNextTask or ReadyForNextBatch based on
// the role's configured receive mode ("task" or "batch").
func ReadyForNext(inboxDir, receiveMode string, now time.Time, stdout, stderr io.Writer) int {
	switch receiveMode {
	case "batch":
		return ReadyForNextBatch(inboxDir, now, stdout, stderr)
	case "task", "":
		return ReadyForNextTask(inboxDir, now, stdout, stderr)
	default:
		return fail(stderr, 2, fmt.Sprintf("INVALID_RECEIVE_MODE: %s", receiveMode))
	}
}

// DoneWithCurrent dispatches to DoneWithCurrentTask or DoneWithCurrentBatch
// based on the role's configured receive mode.
func DoneWithCurrent(inboxDir, receiveMode string, now time.Time, stdout, stderr io.Writer) int {
	switch receiveMode {
	case "batch":
		return DoneWithCurrentBatch(inboxDir, now, stdout, stderr)
	case "task", "":
		return DoneWithCurrentTask(inboxDir, now, stdout, stderr)
	default:
		return fail(stderr, 2, fmt.Sprintf("INVALID_RECEIVE_MODE: %s", receiveMode))
	}
}
