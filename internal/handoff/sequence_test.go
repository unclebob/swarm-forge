package handoff

import (
	"sort"
	"strconv"
	"sync"
	"testing"
)

// TestNextSequenceConcurrent asserts the lock-dir guard actually serializes
// concurrent callers: N goroutines racing NextSequence against the same
// directory must produce N distinct, gapless, monotonic values.
func TestNextSequenceConcurrent(t *testing.T) {
	dir := t.TempDir()
	const n = 50

	results := make([]string, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			seq, err := NextSequence(dir)
			if err != nil {
				t.Errorf("NextSequence: %v", err)
				return
			}
			results[i] = seq
		}(i)
	}
	wg.Wait()

	values := make([]int, n)
	seen := map[int]bool{}
	for i, s := range results {
		v, err := strconv.Atoi(s)
		if err != nil {
			t.Fatalf("sequence %q not numeric: %v", s, err)
		}
		if seen[v] {
			t.Fatalf("duplicate sequence value %d", v)
		}
		seen[v] = true
		values[i] = v
	}
	sort.Ints(values)
	for i, v := range values {
		if v != i+1 {
			t.Fatalf("sequence values not gapless/monotonic from 1: got %v", values)
		}
	}
}
