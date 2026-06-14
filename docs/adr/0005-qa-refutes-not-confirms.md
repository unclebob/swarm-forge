---
status: accepted
---

# QA refutes rather than confirms

Upstream QA verifies that the accepted specification is met and fixes what fails — a *confirm* posture. It converts the specifier's written QA procedures into executable scripts and runs them through the real user interface. The fork flips the posture: QA assumes the build does **not** meet the spec and the acceptance tests are too weak to notice, until it proves otherwise. Its job is to make the claim "this meets the spec and the tests prove it" *fail*.

**Refute against the spec, not beyond it.** QA attacks the specified contract — it hunts specified-but-untested behavior, proves the acceptance tests too weak to catch a real violation, and throws inputs designed to break the specified behavior. It does **not** invent new requirements. A genuinely unspecified gap it stumbles on is not a QA pass/fail; it is a finding that routes back to the specifier. This keeps QA adversarial but bounded, so it never blocks the line on behavior no one agreed to.

**Conversion fidelity.** When QA turns the specifier's written procedures into executable scripts, the script must encode the procedure's full intent — not a weakened version that passes. QA refutes its *own* conversion. This is the highest-leverage guard in the line because the QA end-to-end suite is the one suite the hardener's mutation testing explicitly does not cover: a weak conversion ("test theater" — a green test that asserts nothing real) that hides there is caught by nothing else.

**Findings route back; QA owns the attack, not the routing.** A structural weakness QA surfaces routes back to its cause (a weak acceptance test or an ambiguous spec → the specifier); a local defect QA fixes in place — per the back-routing decision. Refuting QA is the engine that *generates* structural findings; it needs no routing rule of its own.

## Considered options

- **Keep upstream's confirm posture** — rejected: a confirming QA passes test theater (green suites that assert nothing); the defects that survive an otherwise-complete pipeline are exactly the ones a checklist confirms.
- **Refute beyond the spec** — rejected: unbounded; QA becomes a fuzzer that blocks the line on unspecified behavior. Unspecified gaps route back to the specifier instead.

## Pending implementation

- Prompt change on `six-pack`.
- Whether QA's converted end-to-end suite should itself be mutation-tested (the hardener currently ignores it) — the objective way to detect a theatrical conversion rather than relying on QA's self-judgment.
