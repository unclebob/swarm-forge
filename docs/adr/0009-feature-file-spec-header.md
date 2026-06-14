---
status: accepted
---

# Feature files open with a structured spec header

Upstream feature files are pure Gherkin: a `Feature:` line, then scenarios. The fork prepends a **structured spec header** — a block of comment sections the specifier fills in before writing any scenario, captured in a template (`swarmforge/templates/feature.feature`) that the specifier starts every feature from.

The header is the **spec-authoring layer** the reference verification loop puts ahead of the scenarios (its Step 1): the Gherkin scenarios are the contract *by example*, but they cannot state what is out of scope, what was assumed, what non-functional targets apply, or what side effects must be observed. The header carries exactly that — the WHAT/WHY around the examples — so those concerns are stated once, up front, where every downstream role reads them.

**Sections (four-pack):** `TRACKING` (traceability to an issue), `CONTRACT` (every input, every response shape and status, fields deliberately absent), `CONSTRAINTS` (dataset bounds, validation, exclusions), `SEQUENCING` (ordering / async dependencies, defaults `none`), `NFR` (latency, idempotency key+window, in-flight UI, error distinguishability), `SIDE EFFECTS` (public-contract changes, derived artifacts to regenerate, defaults `none`), `SCOPE` (`Does NOT:` exclusions and `ASSUMED:` assumptions). Each section pairs an `Ask:` (the questions that elicit it) with a `Format:` (how to write the answer).

**Six-pack adds an eighth section, `UX INTENT`**, with four dimensions — Visual Composition, Information Hierarchy, Interaction Feel, State Transitions — written as concrete observable statements. Its content and semantics are owned by ADR 0007; the header is merely its home in the feature file. It is six-pack-only because the UX Engineer that consumes it is six-pack-only.

**Address every section; do not fill every section.** `SEQUENCING`, `SIDE EFFECTS`, and (six-pack) `UX INTENT` default to `none`. `none` is a deliberate answer, not a skipped one — and for `UX INTENT`, `none` is the signal that tells the UX Engineer to pass through (ADR 0007). The sections are comments (`#`), so the Gherkin parser ignores them and the acceptance pipeline is unaffected.

## Pending implementation

- Template already drafted on `four-pack` (7 sections) and `six-pack` (8, with `UX INTENT`); land both.
- Specifier phase 1 starts from the template and addresses all header sections before scenarios. Fix the stale count in the **six-pack** specifier prompt: it says "complete all seven header sections" but the six-pack template has eight — change to "eight" (or "all"). Four-pack's "seven" is correct.
