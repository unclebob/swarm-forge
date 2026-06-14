---
status: accepted
---

# Live verification runs through a declared surface harness

Two defects (a screen blink and a runaway key-repeat) once survived a 250-scenario, eight-role pipeline. The cause was structural: no gate ever drove the *running* system through its real production interface — every check ran below the surface, against functions and return values. The fork closes this with a **surface harness doctrine**: the roles that own live verification drive the running system through its actual surface, using a declared tool, and every surface carries a permanent idle baseline.

This is the reference verification loop's execute-and-observe layer (its Steps 5–7) made concrete: build the real thing, drive it through its surface, assert on what comes out.

**Surface tool table (in `engineering.prompt`).** Following the existing language-tool-table pattern, the constitution declares the harness tool per surface type: tmux/PTY for a TUI (`send-keys -l` for raw input at controlled timing, `capture-pane` for screen state over time), Playwright for web, an HTTP client for HTTP APIs, event-injection-at-ingress for headless services. Roles owning live verification — **QA** (both packs) and the **UX Engineer** (six-pack, ADR 0007) — identify the project's surface *from the codebase* and acquire the matching tool before their first harness run, exactly as they acquire language tools.

**No surface field in `project.prompt`.** Roles read the code to know the surface; an explicit declaration would be a meaningless placeholder until the project is customised.

**Every surface carries a mandatory baseline scenario**, committed alongside the flow scenarios: TUI → idle stability (no input, consecutive captures identical, zero scrollback growth); web → idle page loads with no console errors; headless → a no-op event produces no state change. The baseline is what the tetris defects would have hit — they were *idle-state* failures invisible to any flow test, because flow tests only assert while the user is acting.

**The harness scenarios are committed and re-run, not throwaway.** Per verified flow, the live-verification role commits re-runnable scenarios to a project `observation-harness/` directory using the surface tool — alongside the per-surface baseline — as a permanent regression record that must pass against the committed code (on six-pack the UX Engineer authors these, ADR 0007; it also adds golden-file snapshots per state and rendering invariants for structural properties). **QA re-executes the committed `observation-harness/` scenarios before its own final verification**, and routes back (ADR 0004) if a user-facing surface exists but has no scenarios. This is what makes the surface check durable: a defect fixed once stays fixed because its scenario re-runs every cycle.

**QA verifies through the declared surface harness, not "the UI" (idea Q).** Upstream QA's "operate through the user interface only" was right in intent but mechanically silent — it let in-process function calls masquerade as UI verification. The fork replaces the phrase with "through the declared surface harness," and adds an auditable conversion rule: **every Expected bullet maps to a harness assertion, or is explicitly marked `NOT AUTOMATED — <reason>`.** This is the mechanism that makes the conversion-fidelity guard of ADR 0005 checkable rather than a matter of QA's word — a silently dropped bullet becomes a visible marker. Findings route back per ADR 0004.

## Pending implementation

- Add the surface tool table + context-driven acquisition rule to `engineering.prompt` on `four-pack` and `six-pack`.
- Change QA's "through the UI only" to "through the declared surface harness" and add the Expected-bullet → assertion / `NOT AUTOMATED` rule in `QA.prompt` (both packs).
- Require the per-surface baseline scenario to be committed with every feature's flow scenarios.
