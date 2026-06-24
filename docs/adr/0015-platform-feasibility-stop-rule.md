---
status: accepted
---

# Platform-feasibility stop rule

Upstream has no rule for what a role does when a spec requirement conflicts with what the platform can actually deliver. So the role improvises — it ships a silent workaround and leaves a code comment acknowledging the conflict, and behavior diverges from the spec with no one having decided that trade-off. The fork adds a constitution rule: **when a spec requirement conflicts with a real platform capability, stop and report to the user before proceeding.**

**The workaround comment is the smell.** A comment in the code acknowledging a spec-vs-platform conflict is the signal that this rule fired and was suppressed — it is treated as a defect, not an accepted note.

**Narrow on purpose.** This is not a general "stop when confused" rule; it fires specifically on spec-versus-platform-capability conflicts. It lives in the constitution (`workflow.prompt`), so it binds every role that reads the constitution rather than being repeated per role.

## Pending implementation

- `six-pack`: add the rule to `swarmforge/constitution/workflow.prompt`. (four-pack is frozen per ADR 0001 / the change manifest.)
