---
status: accepted
---

# Boundary-logic detection

Boundary files — environmentally-unsuitable adapter shells like TUI drivers, OS input handlers, and environment adapters — are excluded by design from every quality tool's worklist, because they can't run under test. Upstream leaves it there, so pure logic that gets embedded in a boundary file is invisible to mutation, CRAP, and coverage alike. The fork closes that hole: the cleaner (six-pack) / refactorer (four-pack) **also scans boundary files**, at a lower threshold, and extracts the logic when it finds too much.

**A lower threshold, because boundary files should be thin.** Testable source keeps the existing 100-mutation-site split threshold; boundary files trigger at ~15–20 sites — above that, the file holds implementation, not adaptation, and the logic is extracted to a testable module before handoff. Extraction funnels that logic into the normal mutation/CRAP/coverage loops automatically, so no new test type is needed.

**"Tested only through a stripped view" counts as untested.** A test that asserts a simplified projection of the output — ANSI-stripped text when the real output includes the escape codes and newline placement the function exists to add — does not cover the behavior. This is an explicit anti-pattern the cleaner/refactorer treats as missing coverage.

## Pending implementation

- `six-pack`: extend `swarmforge/roles/cleaner.prompt` to scan boundary files at the ~15–20 site threshold and add the stripped-view anti-pattern. (four-pack — whose equivalent role is `refactorer` — is frozen per ADR 0001 / the change manifest; no change there.)
