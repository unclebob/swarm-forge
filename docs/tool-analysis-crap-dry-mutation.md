# Code Quality Tools: CRAP / DRY / Mutation

**Scope:** JavaScript/TypeScript, Python, Rust source targets
**Goal:** Cover all three tool families for all three stacks, matching Uncle Bob's Go/Clj/Java tools

---

## Status

| Stack | CRAP | DRY | Mutation |
|-------|------|-----|----------|
| **JS/TS** | `crap4js` v0.1.0 — **done** | `drywall` — **done** | `mutate` Rust binary — **todo** |
| **Python** | `crap4py` — **todo** | `drywall` — **done** | `mutate4py` — **todo** |
| **Rust** | `cargo-crap` — **reuse** | `drywall` — **done** | `mutate4rs` Rust binary — **todo** |

---

## 1. What Exists Today

### crap4js v0.1.0
- **Install:** `npm install --save-dev github:gabadi/crap4js#v0.1.0`
- **Source:** `github.com/gabadi/crap4js`
- Branch coverage (BRDA) and `?.` CC exclusion are both implemented
- Distributed via GitHub releases — no npm registry

### drywall v0.1.0
- **Install:** `cargo install --git https://github.com/gabadi/drywall --tag v0.1.0`
- **Source:** `github.com/gabadi/drywall`
- Single Rust binary covering JS/TS (OXC), Python (tree-sitter-python), Rust (syn)
- Implements Uncle Bob's AST subtree Jaccard algorithm
- Drop-in CLI compatible with dry4go

### cargo-crap
- Reuse as-is (pre-1.0 but functional)
- Requires lcov.info from `cargo llvm-cov --lcov`

---

## 2. What to Build

### 2.1 crap4py — Python CRAP script (~200 LOC)

New implementation for Python source. Not a port of crap4go (which analyzes Go source) —
crap4py analyzes Python source using Python's own `ast` module.

**Inputs:**
- Python source files (walked from a root directory)
- LCOV tracefile (from pytest-cov or coverage.py with `branch = True` in `.coveragerc`)

**Output:** same column format as crap4go — Function, Module, CC, Cov%, CRAP — sorted worst first

**CC decision points in Python AST:**
`If`, `IfExp` (ternary), `BoolOp` (`and`/`or`), `For`, `While`, `ExceptHandler`, each `match case`

**Branch coverage:** reads `BRDA:` records from LCOV; `cov(m) = BRH_in_range / BRF_in_range × 100`

### 2.2 mutate — Rust binary (JS/TS + Rust)

One binary, two language targets. Ports mutate4go's algorithm to JS/TS and Rust.
OXC parser is already used in drywall — this reuses the same investment.

```
mutate --lang <js|ts|rs> [flags] path/to/file
```

| Flag | Default | Description |
|------|---------|-------------|
| `--test-command` | (required) | Test command to run |
| `--since-last-run` | false | Differential: skip functions whose hash matches manifest |
| `--mutate-all` | false | Force full run, ignore manifest |
| `--reuse-coverage` | false | Skip coverage regeneration |
| `--lcov` | — | Path to pre-generated LCOV file |
| `--max-workers` | 1 | Parallel mutation workers |
| `--scan` | false | Count mutation sites only, no tests |
| `--verbose` | false | Log actions to stderr |

### 2.3 mutate4py — Python mutation script

Same algorithm as the Rust binary, for Python source.
Uses Python's `ast` module. Reads LCOV from pytest-cov / coverage.py.

```
mutate4py [flags] path/to/file.py
```

Same flags as `mutate`.

---

## 3. Key Decisions

### Why port mutation instead of reusing StrykerJS / mutmut / cargo-mutants

All three existing tools lack the property that makes mutate4go useful in practice:
an embedded-in-source manifest.

| Property | mutate4go | StrykerJS | mutmut | cargo-mutants |
|----------|-----------|-----------|--------|---------------|
| Manifest stored in source file | Yes | No | No | No |
| Survives repo clone | Yes | No | No | No |
| Team-shared automatically | Yes | No | No | No |
| Zero CI setup for incremental | Yes | No | No | No |
| Incremental granularity | per-function hash | per-mutant position | per-function hash | line (external diff) |

The manifest is embedded as comments in the source file footer, committed with the code.
Any developer who pulls the repo gets differential reruns automatically. StrykerJS's
incremental JSON and mutmut's SQLite cache are external artifacts that each require
explicit CI cache configuration and provide nothing to developers working locally.

### Why crap4py is not a port of crap4go

crap4go analyzes **Go** source. crap4py analyzes **Python** source. They implement the
same CRAP formula but are completely separate tools using their language's native AST.
There is no Python port of crap4go to reuse — it does not exist.

### Why one mutate binary covers JS/TS and Rust

OXC (JS/TS parser) and `syn` (Rust parser) are both Rust crates. Building them in one
binary reuses the OXC investment already made for drywall and avoids distributing two
separate binaries.

---

## 4. Mutation Algorithm

Same for all three ports. Matches mutate4go's implementation.

**Per run:**
1. Parse source file → walk functions → normalize each (identifiers → `_ID`, literals → `_LIT`) → hash (FNV-1a)
2. Read embedded manifest from file footer; skip functions whose hash matches
3. For changed functions: read LCOV → for each covered mutation site → apply operator → run test command → restore
4. Write updated manifest to file footer

**Operator set** (Uncle Bob's spec):

| Category | Mutations |
|----------|-----------|
| Arithmetic | `+` ↔ `-`, `*` → `/` |
| Comparison | `>` ↔ `>=`, `<` ↔ `<=` |
| Equality | `==` ↔ `!=` |
| Boolean | `true` ↔ `false` |
| Logical | `&&` ↔ `||` |
| Constant | `0` ↔ `1` (inline, in expressions) |
| Unary | remove `-a` → `a`, remove `!a` → `a` |
| Null | replace return value with `null` / `None` |

**Manifest format** (identical across all three, language-native comments):

```python
# mutate4py-manifest: version=1
# fn:compute_score hash=a3f9c1d2 lines=5-25 tested=2026-06-21
# fn:validate_input hash=b7e2a4f1 lines=30-48 tested=2026-06-21
```

```typescript
// mutate4js-manifest: version=1
// fn:computeScore hash=a3f9c1d2 lines=5-25 tested=2026-06-21
```

---

## 5. Open Questions

- Whether dry4go's 0.82 Jaccard threshold needs calibration for JS/Python codebases
- Whether `syn` or `tree-sitter-rust` is the better choice for mutate4rs normalization
  (`syn` has higher semantic fidelity; `tree-sitter-rust` is consistent with the other parsers in drywall)
- Whether cargo-crap's pre-1.0 status is a blocker or acceptable for internal use

---

## Appendix A — LCOV Format Reference

LCOV tracefile format is produced by Jest, Vitest, c8, nyc, pytest-cov, coverage.py,
cargo-llvm-cov, and cargo-tarpaulin.

| Record | Syntax | Meaning |
|--------|--------|---------|
| `SF` | `SF:<path>` | Opens a source file section |
| `FN` | `FN:<start>[,<end>],<name>` | Function declaration |
| `FNDA` | `FNDA:<count>,<name>` | Function execution count |
| `BRDA` | `BRDA:<line>,<block>,<branch>,<taken>` | Branch edge (`taken='-'` means unreachable) |
| `BRF` | `BRF:<count>` | Total branch records |
| `BRH` | `BRH:<count>` | Branch records with taken > 0 |
| `DA` | `DA:<line>,<count>` | Line execution count |
| `end_of_record` | `end_of_record` | Closes a source file section |

CRAP uses branch coverage: `cov(m) = BRH_in_function / BRF_in_function × 100`.
Dead branches (`taken='-'`) are excluded from both numerator and denominator.

**coverage.py requirement:** add `branch = True` to `.coveragerc` to emit BRDA records.

---

## Appendix B — Uncle Bob Reference CLIs

### crap4go
```
crap4go [--test-command <cmd>] [--max-workers <n>] [path-fragment ...]
```
Deletes stale coverage → runs test command → parses LCOV + AST → prints CRAP per function, sorted worst first.

### dry4go
```
dry4go [--threshold 0.82] [--min-lines 4] [--min-nodes 20] [--format text|json] [path ...]
```

### mutate4go
```
mutate4go [--since-last-run] [--mutate-all] [--scan] [--test-command <cmd>] [--max-workers 1] path/to/file.go
```
Embedded manifest in source file footer. Differential by default when manifest exists.
