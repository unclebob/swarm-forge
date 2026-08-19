package trust

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestEnsureTrustedNoOpWhenConfigMissing(t *testing.T) {
	home := t.TempDir()
	n, err := EnsureTrusted(home, []string{"/some/dir"})
	if err != nil || n != 0 {
		t.Fatalf("n=%d err=%v", n, err)
	}
}

func TestEnsureTrustedCreatesNewEntryAndBacksUp(t *testing.T) {
	home := t.TempDir()
	configPath := Path(home)
	if err := os.WriteFile(configPath, []byte(`{"other": "value"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	dir := t.TempDir()
	n, err := EnsureTrusted(home, []string{dir})
	if err != nil {
		t.Fatalf("EnsureTrusted: %v", err)
	}
	if n != 1 {
		t.Fatalf("expected 1 newly trusted dir, got %d", n)
	}

	if _, err := os.Stat(configPath + ".swarmforge.bak"); err != nil {
		t.Fatalf("backup not written: %v", err)
	}

	raw, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	var cfg map[string]interface{}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg["other"] != "value" {
		t.Fatalf("unrelated top-level key was lost: %+v", cfg)
	}
	abs, _ := filepath.Abs(dir)
	projects := cfg["projects"].(map[string]interface{})
	entry := projects[abs].(map[string]interface{})
	if entry["hasTrustDialogAccepted"] != true {
		t.Fatalf("entry not marked trusted: %+v", entry)
	}
	if _, ok := entry["allowedTools"]; !ok {
		t.Fatalf("new entry should get an allowedTools field: %+v", entry)
	}
}

func TestEnsureTrustedPreservesExistingEntryFields(t *testing.T) {
	home := t.TempDir()
	configPath := Path(home)
	dir := t.TempDir()
	abs, _ := filepath.Abs(dir)
	initial := map[string]interface{}{
		"projects": map[string]interface{}{
			abs: map[string]interface{}{
				"allowedTools":           []interface{}{"Bash", "Read"},
				"hasTrustDialogAccepted": false,
				"someOtherField":         "keep-me",
			},
		},
	}
	raw, _ := json.Marshal(initial)
	if err := os.WriteFile(configPath, raw, 0o644); err != nil {
		t.Fatal(err)
	}

	n, err := EnsureTrusted(home, []string{dir})
	if err != nil || n != 1 {
		t.Fatalf("n=%d err=%v", n, err)
	}

	raw2, _ := os.ReadFile(configPath)
	var cfg map[string]interface{}
	json.Unmarshal(raw2, &cfg)
	entry := cfg["projects"].(map[string]interface{})[abs].(map[string]interface{})
	if entry["hasTrustDialogAccepted"] != true {
		t.Fatalf("not marked trusted: %+v", entry)
	}
	if entry["someOtherField"] != "keep-me" {
		t.Fatalf("existing field was clobbered: %+v", entry)
	}
	tools := entry["allowedTools"].([]interface{})
	if len(tools) != 2 {
		t.Fatalf("existing allowedTools was overwritten: %+v", tools)
	}
}

func TestEnsureTrustedIdempotentSkipsAlreadyTrusted(t *testing.T) {
	home := t.TempDir()
	configPath := Path(home)
	dir := t.TempDir()
	abs, _ := filepath.Abs(dir)
	initial := map[string]interface{}{
		"projects": map[string]interface{}{
			abs: map[string]interface{}{"hasTrustDialogAccepted": true},
		},
	}
	raw, _ := json.Marshal(initial)
	if err := os.WriteFile(configPath, raw, 0o644); err != nil {
		t.Fatal(err)
	}

	n, err := EnsureTrusted(home, []string{dir})
	if err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("expected no changes for an already-trusted dir, got %d", n)
	}
	if _, err := os.Stat(configPath + ".swarmforge.bak"); !os.IsNotExist(err) {
		t.Fatalf("should not have written a backup when nothing changed")
	}
}

func TestEnsureTrustedDeduplicatesDirs(t *testing.T) {
	home := t.TempDir()
	configPath := Path(home)
	if err := os.WriteFile(configPath, []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	n, err := EnsureTrusted(home, []string{dir, dir, dir})
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("expected deduplication to yield 1 change, got %d", n)
	}
}
