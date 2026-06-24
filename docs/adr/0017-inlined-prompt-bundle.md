---
status: accepted
---

# The agent context is one inlined, deduplicated prompt bundle

Upstream builds a role's launch context by concatenating its constitution and role prompt, following `*.prompt` references with a simple recursive read — no deduplication and no structure, just text appended to text. The fork replaces this with a **resolved prompt bundle**: a breadth-first walk over the `*.prompt` reference graph that visits each file once (dedup by resolved path, already-visited references skipped so a cycle cannot loop), emitted as a single XML envelope `<swarmforge_agent_context>` with each source file in its own `<file>` block.

**The bundle is the unit of delivery, not just of launch.** Clear-first delivery (ADR 0002) wipes the session with `/clear` and then *re-injects the role bundle* before every task. That re-injection needs a single, complete, deduplicated context to re-send — which is exactly what the resolver produces. A naive recursive concatenation is fine to build once at launch but is the wrong shape to re-send reliably on every handoff.

**It is the prerequisite for knowledge injection.** ADR 0014 appends the project's `AGENTS.md` and the role's `.agents/` file into this same envelope. There is nowhere to append them, and no well-defined boundary to append them at, until the context is a structured bundle rather than flat concatenated text. 0014 builds on top of the bundle.

**Why an XML envelope.** Explicit `<file>` boundaries let the agent tell its constitution from its role prompt from its promoted knowledge, instead of inferring breaks in a wall of concatenated text; and the BFS dedup keeps a cross-referenced constitution (articles, the dependency manifest) from appearing two or three times.

This divergence is taken in its **minimal translated form**: the resolver and envelope are ported onto upstream's current tmux delivery harness.
