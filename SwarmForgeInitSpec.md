# Product Specification: SwarmForge

**Version:** 1.0  
**Author:** Justin Martin  
**Status:** Approved for MVP Development  

## 1. Product Overview

**SwarmForge** is a lightweight, self-hosted, tmux-based orchestration platform that coordinates swarms of AI agents to build production-grade software with unbreakable professional discipline.

It solves the fundamental risk of agentic development — **undisciplined, brittle, unmaintainable code** — by embedding the four core Clean Code practices (plus linting) as a living **Constitution** that every agent must obey on every task.

SwarmForge turns raw AI coding speed into **reliable, scalable, maintainable engineering output** while remaining fully observable and controllable by the human user.

**Tagline:**  
*Disciplined agents build better software — faster and more reliably.*

## 2. Business Goals & Objectives

- Demonstrate that agentic development can be **professional-grade** when governed by strict craftsmanship rules.
- Provide developers with a practical, runnable platform to experiment with and adopt disciplined agentic workflows.
- Dogfood the platform by using the swarm to extend and improve SwarmForge itself.
- Create a foundational layer that can scale from local tmux sessions to distributed cloud swarms and a full Electron GUI.
- Educate the community on TDD, E2E Gherkin, mutation testing, complexity control, and linting in the age of AI agents.

## 3. Target Users

- Individual developers and indie hackers exploring agentic AI coding.
- Software engineering teams evaluating AI-augmented development practices.
- Clean Code enthusiasts and Uncle Bob followers who want to preserve craftsmanship in the AI era.
- Educators and content creators building tutorials on disciplined agentic development.
- Early adopters who want to run their own observable AI coding swarm locally.

## 4. Core Features

| Feature | Description | MVP Status |
|---------|-------------|------------|
| **Constitution Engine** | Central `Constitution.md` file that every agent reads at startup. Defines 5 non-negotiable rules. | Yes |
| **Agent Swarm Orchestration** | Spawns and coordinates multiple specialized agents (Architect, Coder, TDD Guardian, E2E Interpreter, Mutation Hunter, Complexity Enforcer, Linter Guardian) in named tmux panes. | Yes |
| **Real-Time Collaboration** | Agents communicate via shared filesystem, tmux pane output, and structured log files. Human can observe and intervene in any pane. | Yes |
| **TDD Enforcement (Rule 1)** | Agents must follow Red → Green → Refactor cycle. Production code forbidden until failing test exists. | Yes |
| **E2E Gherkin Interpreter (Rule 2)** | Auto-parses `.feature` files into executable end-to-end tests. Gherkin is the single source of truth. | Yes |
| **Mutation Testing (Rule 3)** | Mutation Hunter generates and kills mutants on every change. ≥90% kill rate required. | Yes |
| **Cyclomatic Complexity + CRAP Enforcement (Rule 4)** | Complexity Enforcer rejects any method >4 complexity or CRAP ≥30 and forces refactor. | Yes |
| **Linter Guardian (Rule 5)** | Runs language-specific linter with zero-tolerance policy. Auto-fixes safe issues. | Yes |
| **Pre-Commit / Pre-Merge Hooks** | Automatic validation pipeline that blocks any commit violating the Constitution. | Yes |
| **Live Metrics Dashboard** | Real-time display (in tmux) of test coverage, mutation score, complexity metrics, and Gherkin status. | Yes |
| **Self-Dogfooding** | SwarmForge uses its own swarm to implement new features in SwarmForge. | Yes |
| **Task Submission** | Users submit work via main Architect pane or by adding a new Gherkin `.feature` file. | Yes |

## 5. How It Works (High-Level Workflow)

1. **Launch**  
   User runs `./swarmforge.sh` → tmux session starts with pre-configured panes for each agent role.

2. **Task Intake**  
   User describes a feature in the Architect pane.

3. **Constitution-Led Execution**  
   - Architect agent reads Constitution and translates task into Gherkin scenarios.  
   - E2E Interpreter generates executable tests from Gherkin.  
   - TDD Guardian drives Red → Green → Refactor cycle.  
   - Mutation Hunter runs mutation tests and forces new tests for surviving mutants.  
   - Complexity Enforcer + Linter Guardian validate every file change.

4. **Continuous Validation**  
   Every agent action triggers the full validation pipeline. Any violation halts progress and explains the exact rule broken.

5. **Human Oversight**  
   User watches live reasoning and code generation in real time. Can approve, reject, or give clarification in any pane.

6. **Completion**  
   Only when all five rules are satisfied does the swarm mark the task complete and commit the changes.

The entire process is observable, auditable, and repeatable.

## 6. System Architecture

- **Runtime:** tmux + bash + AI agent backend (Cursor, Continue.dev, custom agent framework, or OpenAI/Anthropic API).
- **Agent Communication:** Shared filesystem + structured JSON logs + tmux pane output.
- **Constitution:** Plain Markdown file loaded into every agent’s system prompt.
- **Languages Supported:** Language-agnostic (MVP focused on Go/TypeScript; extensible via config).
- **Storage:** All code, tests, Gherkin files, and logs live in the project repo.
- **Extensibility Points:** Plugin system for new agent roles, additional languages, and future distributed mode.

## 7. Non-Functional Requirements

- **Performance:** Responsive enough for real-time observation (agents should complete cycles in <2 minutes for small tasks).
- **Observability:** Every decision, test run, and metric must be visible to the human user.
- **Reliability:** Swarm must never produce code that violates the Constitution.
- **Security:** Runs locally; no external data exfiltration unless explicitly configured.
- **Usability:** Minimal setup — clone, run script, start coding.

## 8. Future Roadmap (Post-MVP)

- Electron GUI for non-technical users (visual agent pane management, drag-and-drop Gherkin editor).
- Distributed swarm mode (agents across multiple machines / cloud instances).
- Advanced agent roles (Security Auditor, Performance Optimizer, Documentation Writer).
- Web-based dashboard with historical metrics and swarm analytics.
- Plugin marketplace for community-contributed agents and interpreters.
- Integration with CI/CD pipelines for remote swarm execution.

## 9. Assumptions & Constraints

- Assumes user has tmux installed and access to a capable LLM backend.
- MVP is single-machine only.
- Agents are powered by current-generation LLMs (prompt engineering is key to enforcement).
- Human remains the ultimate authority (can override Constitution if explicitly documented).

## 10. Success Metrics

- SwarmForge successfully builds its own next major feature using only itself.
- ≥95% of generated code passes all five constitutional rules on first attempt.
- Community adoption (stars, forks, YouTube tutorial views).
- Measurable improvement in code quality vs. traditional agentic workflows.

---

**Approved by:** Justin Martin  
**Next Review:** After MVP core is live and self-dogfooded.

This Product Specification serves as the single source of truth for all SwarmForge development. Every feature added must comply with the Constitution.md and this spec.
