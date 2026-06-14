---
status: accepted
---

# Dependencies are declared by fidelity tier in a manifest

A scenario that rests on an emulated dependency the emulator does not actually implement passes green and proves nothing — the system was never exercised against the behavior the scenario claims to cover. The fork makes dependency fidelity **explicit and refusable** through a new constitution sub-file, `swarmforge/dependency-manifest.prompt`, that declares every dependency beyond the system itself by fidelity tier. This is the reference loop's Digital-Twin discipline: a twin is only trustworthy if its fidelity — and its gaps — are stated.

**A separate constitution file, not `project.prompt`.** The manifest holds project-specific dependency data that would clutter `project.prompt`; it lives in its own file, auto-resolved by the same bundle resolver as the other constitution sub-files. It ships on both packs and defaults to `(none)` — a project with no external dependencies declares nothing.

**Three tiers (the system itself is always implicit).** Tier 1 — owned infrastructure run locally as the real engine (e.g. Postgres in Docker). Tier 2 — stateful, protocol-level emulation (preference order: vendor-official emulator > established third-party > a swarm-built twin only as last resort). Tier 3 — external domain the swarm does not own (third-party APIs, other teams' services), wire-level stubbed against a referenced contract. Entry format: `name: tier N; implementation; gaps: <description or none>`.

**Declared gaps are machine-readable and binding.** The specifier and QA must not write or accept scenarios that rest on a declared gap — so a known emulator limitation can never masquerade as covered behavior. Supporting rules: every harness scenario starts from a declared seed state and resets dependency state between scenarios; tier-2/3 dependencies must expose post-interaction state for assertion (the message landed in the emulator's outbox), so scenarios assert *effects*, not only the system's own surface; and a swarm-built twin must not be authored by the role that wrote the system code it emulates, and must be validated against recorded real traffic or the vendor's official SDK tests.

**The specifier owns the manifest.** Before writing scenarios it reads the manifest; if a feature touches an external system not yet declared, it stops, proposes name/tier/implementation/gaps to the user, and waits for approval before adding the entry — tier assignment is an architectural decision the user must own, mirroring the other specifier approval gates.

## Pending implementation

- Add `swarmforge/dependency-manifest.prompt` (tier definitions inline, body `(none)`) on `four-pack` and `six-pack`.
- Add the read-manifest / propose-on-undeclared rule to `specifier.prompt` (both packs); QA's refusal of gap-resting scenarios is part of refuting QA (ADR 0005).
