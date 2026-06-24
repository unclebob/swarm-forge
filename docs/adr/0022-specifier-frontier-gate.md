---
status: accepted
---

# Specifier gates on frontier intent, not on formal spec

Upstream gates human review on the full formal spec — Gherkin + QA suite — before handing off to the coder. The specifier writes everything, then asks for approval.

The fork moves the gate earlier: the specifier drafts a **frontier brief** (one-sentence intent, 2–4 prose scenarios, and explicit fog exclusions) and gets confirmation before generating any formal artifact. Everything after confirmation — `grill-with-docs`, Gherkin with headers, QA suite, handoff — runs autonomously.

**Why:** Gherkin and its header sections are a mechanical encoding of confirmed intent, not a new decision. Reviewing the encoding adds no human judgment — only friction. The frontier brief is the decision point. The formal spec is how that decision is expressed.

**Fog of war:** the brief names only what is knowable and decidable at the frontier. Uncertain items are listed as "not in scope" — explicitly excluded, not silently omitted — and become `Does NOT:` entries in the Gherkin SCOPE header. The specifier does not plan past the fog.

**Contradiction rule:** during autonomous generation, the specifier may add scenarios that are natural consequences of confirmed behavior. The only trigger to surface to the user is a contradiction or mismatch — something that makes the confirmed brief inconsistent or impossible to implement as specified.

**Handoff** is fully automatic after brief confirmation; there is no second human gate.
