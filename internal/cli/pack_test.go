package cli_test

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"github.com/torratdev/swarmforge/internal/cli"
)

func testEnv(stdout, stderr *bytes.Buffer) cli.Env {
	return cli.Env{Stdout: stdout, Stderr: stderr, Getenv: func(string) string { return "" }, Now: time.Now}
}

func TestRunPackListShowsAllThreePacks(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := cli.RunPackList(testEnv(&stdout, &stderr))
	if code != 0 {
		t.Fatalf("code=%d stderr=%s", code, stderr.String())
	}
	for _, want := range []string{"two-pack", "four-pack", "six-pack"} {
		if !strings.Contains(stdout.String(), want) {
			t.Fatalf("pack list missing %q:\n%s", want, stdout.String())
		}
	}
}

func TestRunPackLintAllPacksPass(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := cli.RunPackLint(testEnv(&stdout, &stderr), "")
	if code != 0 {
		t.Fatalf("code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	for _, want := range []string{"two-pack: OK", "four-pack: OK", "six-pack: OK"} {
		if !strings.Contains(stdout.String(), want) {
			t.Fatalf("missing %q in:\n%s", want, stdout.String())
		}
	}
}

func TestRunPackLintUnknownPack(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := cli.RunPackLint(testEnv(&stdout, &stderr), "nonexistent")
	if code != 1 {
		t.Fatalf("expected failure for an unknown pack, code=%d", code)
	}
}
