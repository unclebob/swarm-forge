---
status: accepted
---

# The swarm pins and upgrades its own dependencies

The swarm depends on an external skill set (the `entire` skills) that it installs into the target project's `.claude/skills/`. The fork makes that dependency **pinned and upgradable**: a SHA recorded in `swarmforge/scripts/install-pins.conf`, installed automatically at launch, and refreshable through an explicit `./swarm upgrade`.

**Pinned, not floating.** `install-pins.conf` records `ENTIRE_SKILLS_SHA`; the swarm installs exactly that SHA and writes it to `.swarmforge/skills-installed`. Moving versions means bumping the pin and committing it on `main` — so two runs weeks apart install identical skills, and an upstream skill change can never alter a run mid-flight.

**Auto-install is launcher bootstrap, not project setup.** `ensure_skills_installed` runs at launch: if the recorded sentinel matches the pin it does nothing, otherwise it (re)installs. This is the program fetching its own dependencies — the same category as `./swarm` self-fetching its scripts — and is deliberately kept separate from the two things that are *not* automatic: project provisioning (the `setup-swarm` skill, ADR 0003) and role work (the idle gate, ADR 0002). It does not contradict "roles do nothing at startup": the launcher, not any role, installs the skills, and it does so before a single role starts. Skill installation therefore lives here, not in `setup-swarm`.

**`./swarm upgrade` refreshes the installation.** It re-pulls the scripts (from `main`) and the role prompts (from the branch recorded in `.swarmforge/source-branch`) and forces a skill reinstall. `source-branch` is written on first run so `upgrade` knows whether a checkout's prompts came from `six-pack` or `four-pack`.

**Why the swarm needs this at all.** A tool whose job is to adapt arbitrary projects must itself be reproducible and updatable in place; without a pin, runs drift; without `upgrade`, an operator's only way to take a fix is to re-clone.

## Pending implementation

- `main`: `install_skills` + `ensure_skills_installed` (pin-aware, idempotent) in `swarmforge.sh`, plus the new `swarmforge/scripts/install-pins.conf`. Source: `backup/main-pre-reset` (`~L946`).
- Runnable branches (`six-pack`/`four-pack`, root `swarm` bootstrap — not `main`): the `upgrade` subcommand, `download_from_main`, `write_source_branch`, and `.swarmforge/source-branch` tracking. Source: `swarm` bootstrap commit `8994322`.
