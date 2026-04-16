# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

SwarmForge exists to solve the core problem of agentic development: **chaos**.

Left unchecked, AI agents produce code quickly but often without discipline, leading to brittle, untested, hard-to-maintain software. SwarmForge changes that by embedding **strict professional craftsmanship** directly into the platform.

It enforces four foundational clean code disciplines — plus static linting — as an unbreakable **Constitution**. Every agent in the swarm must obey these rules on every task. The result is fast, scalable, and genuinely high-quality software produced reliably at swarm speed.

SwarmForge turns raw AI coding power into **disciplined, trustworthy engineering**.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge.conf`
- Creates one tmux session and one Terminal window per configured role
- Reads behavior from project-local `roles/<role>.prompt` files
- Supports per-role backends such as `claude`, `codex`, or `none`
- Generates helper scripts in the working directory for logging, notification, and cleanup
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `roles/<role>.prompt` in the working tree being orchestrated.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, or no agent at all.
- **Observable Swarm** — Open one Terminal window per role and watch the sessions in real time.
- **Self-Hosted & Lightweight** — Runs locally in tmux and Terminal with minimal machinery.

## How It Works (High Level)

1. Create `swarmforge.conf` in the target working directory.
2. Create a `roles/` directory beside it with one `<role>.prompt` file per configured role.
3. Run `./swarmforge.sh <working-directory>` or run it from inside that directory.
4. SwarmForge creates tmux sessions, opens Terminal windows, and launches each configured backend.
5. Roles communicate through `./notify-agent.sh <role-or-index> "message"` and log via `./swarm-log.sh`.

Example config:

```conf
window architect claude
window coder codex
window e2e codex
window logger none
```

`logger` is a utility role. When configured with `none`, it tails `logs/agent_messages.log`.

## Who Is SwarmForge For?

- Developers who want to harness AI agents without sacrificing code quality
- Teams exploring agentic development practices
- Anyone tired of “AI wrote it” meaning “now I have to rewrite it”
- Clean Code enthusiasts who believe discipline still matters in the age of agents

## Getting Started

```bash
git clone https://github.com/LupusDei/swarm-forge.git
cd swarm-forge
chmod +x swarmforge.sh
mkdir my-project
cd my-project
cat > swarmforge.conf <<'EOF'
window architect claude
window coder codex
window e2e codex
window logger none
EOF
mkdir roles
cat > roles/architect.prompt <<'EOF'
You are the architect. Read Contitution.md and follow it.
EOF
cat > roles/coder.prompt <<'EOF'
You are the coder. Read Contitution.md and follow it.
EOF
cat > roles/e2e.prompt <<'EOF'
You are the e2e role. Read Contitution.md and follow it.
EOF
/path/to/swarm-forge/swarmforge.sh .
