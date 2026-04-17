# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

SwarmForge exists to solve the core problem of agentic development: **chaos**.

Left unchecked, AI agents produce code quickly but often without discipline, leading to brittle, untested, hard-to-maintain software. SwarmForge changes that by embedding **strict professional craftsmanship** directly into the platform.

It enforces four foundational clean code disciplines — plus static linting — as an unbreakable **Constitution**. Every agent in the swarm must obey these rules on every task. The result is fast, scalable, and genuinely high-quality software produced reliably at swarm speed.

SwarmForge turns raw AI coding power into **disciplined, trustworthy engineering**.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarm-forge/swarm-forge.conf`
- Creates one tmux session and one Terminal window per configured role
- Reads behavior from project-local `swarm-forge/<role>.prompt` files plus `swarm-forge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, or `none`
- Uses the checked-in helper scripts that ship with SwarmForge for logging, notification, and cleanup
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarm-forge/swarm-forge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarm-forge/<role>.prompt` in the working tree being orchestrated.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, or no agent at all.
- **Observable Swarm** — Open one Terminal window per role and watch the sessions in real time.
- **Self-Hosted & Lightweight** — Runs locally in tmux and Terminal with minimal machinery.

## How It Works (High Level)

1. Create a `swarm-forge/` directory in the target working directory.
2. Put `swarm-forge.conf`, `constitution.prompt`, and one `<role>.prompt` file per configured role inside it.
3. Run `./swarmforge.sh <working-directory>` or run it from inside that directory.
4. SwarmForge creates tmux sessions, opens Terminal windows, and launches each configured backend.
5. Roles communicate through the SwarmForge helper scripts such as `notify-agent.sh` and `swarm-log.sh`.

Example config:

```conf
window architect claude
window coder codex
window e2e codex
window logger none
```

`logger` is a utility role. When configured with `none`, it tails `logs/agent_messages.log`.

The launcher expects these helper scripts to exist beside `swarmforge.sh`:

- `notify-agent.sh`
- `swarm-log.sh`
- `swarm-cleanup.sh`

## Who Is SwarmForge For?

- Developers who want to harness AI agents without sacrificing code quality
- Teams exploring agentic development practices
- Anyone tired of “AI wrote it” meaning “now I have to rewrite it”
- Clean Code enthusiasts who believe discipline still matters in the age of agents

## Getting Started

```bash
git clone https://github.com/unclebob/swarm-forge.git
cd swarm-forge
chmod +x swarmforge.sh
mkdir my-project
cd my-project
mkdir swarm-forge
cat > swarm-forge/swarm-forge.conf <<'EOF'
window architect claude
window coder codex
window e2e codex
window logger none
EOF
cat > swarm-forge/constitution.prompt <<'EOF'
Read this constitution and obey it on every task.
EOF
cat > swarm-forge/architect.prompt <<'EOF'
You are the architect. Read swarm-forge/constitution.prompt and follow it.
EOF
cat > swarm-forge/coder.prompt <<'EOF'
You are the coder. Read swarm-forge/constitution.prompt and follow it.
EOF
cat > swarm-forge/e2e.prompt <<'EOF'
You are the e2e role. Read swarm-forge/constitution.prompt and follow it.
EOF
/path/to/swarm-forge/swarmforge.sh .
