<p align="center" style="color: red; font-weight: bold; font-size: 2em; font-style: italic; text-decoration: underline;">
Do not spend any money on a bankrbot SWARM token.
</p>

# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

This `main` branch is documentary: it explains the system and carries the shared operational scripts and default constitution articles. Pack branches (`two-pack`, `four-pack`, `six-pack`) are templates. `get-swarm-forge` installs all of them into a forge `packs/` directory; **New Project** instantiates one pack into `projects/<name>/`.

SwarmForge is an agent coordination system that facilitates communication between agents working in different git worktrees.

It provides a shared structure for role-specific prompts, worktree assignment, tmux sessions, and message passing so multiple agents can collaborate on the same project without stepping on each other.

## Branches

Pack templates live on dedicated branches. Each branch contains the `swarmforge/swarmforge.conf`, local constitution articles, and role prompts for one workflow. `get-swarm-forge` copies all of them into `packs/` along with host scripts from `main`.

### `two-pack`

`two-pack` is the quick backend workflow. Use it for small tasks that benefit from fast coding without the overhead of Gherkin and acceptance testing, while still preserving backend refactoring and hardening.

- `coder` implements requested behavior with TDD and unit tests.
- `cleaner` batches coder handoffs and performs cleanup, CRAP and DRY review, architectural review, encapsulation and separation-of-concerns fixes, and language mutation hardening.

The card moves `coder` -> `cleaner`, then to Done. Cleaner also sends a merge-only copy back to coder. Use this branch when you want a tight implementation/refinement loop without specification, QA, property-test, or acceptance-test roles.

### `four-pack`

`four-pack` is the compact specification workflow. Use it for moderate projects that require Gherkin specification and some architectural consideration without splitting every quality gate into its own agent:

- `specifier` turns user intent into precise Gherkin acceptance specifications and asks for approval before handoff.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `refactorer` performs behavior-preserving cleanup, coverage improvement, CRAP and DRY review, mutation-site scans, and property-test support.
- `architect` owns high-level structure, dependency direction, mutation hardening, DRY review, soft Gherkin mutation, and final completion notification.

The card moves `specifier` -> `coder` -> `refactorer` -> `architect`, then to Done. Refactorer also sends a merge-only copy back to coder. Architect also sends merge-only copies to every earlier role. Use this branch when you want disciplined development without splitting cleanup, architecture, hardening, and QA into separate agents.

### `six-pack`

`six-pack` is the full workflow. Use it for major projects that require full specification, up-front QA, backend verification, and significant architectural consideration. It separates each major quality gate into its own role:

- `specifier` turns user intent into accepted Gherkin specifications and end-to-end QA procedures.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `cleaner` performs local behavior-preserving cleanup, coverage improvement, CRAP and DRY review, and mutation-site scans.
- `architect` reviews module structure, boundaries, dependency direction, and property-test coverage.
- `hardender` performs mutation hardening, language mutation, CRAP and DRY verification, and soft Gherkin mutation.
- `QA` converts the specifier's QA procedures into executable scripts, runs final user-interface verification, checks handoff consistency, and sends completion notifications.

The card moves `specifier` -> `coder` -> `cleaner` -> `architect` -> `hardender` -> `QA`, then to Done. Cleaner also sends a merge-only copy back to coder. Architect and QA also send merge-only copies to every earlier role. Use this branch when you want each review and verification concern owned by a separate agent.

### `simple-windows`

`simple-windows` is a tag on `main`, not a workflow branch. It marks the last commit before the pack cockpit: one Terminal window per role, no dashboard, and no `window-invisible`. It does not sit on `squad` or the other squad branches.

```sh
git fetch origin tag simple-windows
git checkout simple-windows
```

Or download that snapshot:

```sh
curl -L "https://github.com/unclebob/swarm-forge/archive/refs/tags/simple-windows.tar.gz" | tar -xz --strip-components=1
```

Do not use `simple-windows` as `BRANCH=` in the pack getting-started command below; that command is for `two-pack`, `four-pack`, and `six-pack`.

## Prerequisites

SwarmForge runs locally. Before starting a runnable branch, make sure the target machine has:

- `zsh`
- `git`
- `tmux`
- Babashka (`bb`)
- At least one configured agent backend, such as `codex`, `claude`, `copilot`, or `grok`

### Windows

Native Windows (no WSL) is supported via [MSYS2](https://www.msys2.org/), which provides `zsh`, `tmux`, and `git`:

```sh
pacman -S zsh tmux git curl tar gawk
```

Install Babashka natively (e.g. via `winget install Babashka.Babashka` or `scoop install babashka`) and make sure the directories holding `bb`, your agent backend CLI, and `get-swarm-forge` are all on the MSYS2 shell's `PATH` (MSYS2's default `PATH` does not inherit the full Windows `PATH`; add the missing directories to `~/.zshrc`/`~/.bashrc`). Run everything — `get-swarm-forge` and `./swarm` — from an MSYS2 `zsh` shell, not from Git Bash or PowerShell.

## Getting Started

Install the `get-swarm-forge` helper somewhere on your `PATH`, such as `~/cmds` or `~/bin`:

```sh
mkdir -p ~/cmds
cp get-swarm-forge ~/cmds/get-swarm-forge
chmod +x ~/cmds/get-swarm-forge
```

Make sure that utility directory is on your shell `PATH`, then run the helper in
the directory that will be the **forge** (not a single project):

```sh
get-swarm-forge
```

`get-swarm-forge` downloads `main` and every pack branch (`two-pack`,
`four-pack`, `six-pack`). It installs host scripts under `swarmforge/`, pack
templates under `packs/`, and an empty `projects/` directory. It does not
turn the current directory into one pack.

Start the host dashboard:

```sh
./swarm
```

`./swarm` starts the dashboard and the **lieutenant** only. It does not start
project agents. Startup prints a **Dashboard:** URL (also written to
`.swarmforge/dashboard-url`) and opens it in the browser when `open` is
available.

Create a project from the dashboard with **New Project** (name, mission,
pack, optional GitHub `owner/repo`, editable conf). That writes
`projects/<name>/` including `mission.md`, gives that directory its own
git repo (or uses the clone), and starts that pack. The pack's `master`
role works in that repo. **Open Project** starts an existing directory
under `projects/`. **Close** on a project header stops that pack and
leaves the directory.

Set `SWARMFORGE_OPEN_BROWSER=0` before `./swarm` to skip the browser open. The dashboard still starts; visit the printed URL.

To stop everything, click **Teardown** in the dashboard header and confirm.
That closes every open project, then kills the lieutenant, tmux, and the
dashboard. Directories under `projects/` stay on disk. After a later
`./swarm`, nothing is running until you Open Project.

While a swarm is active, SwarmForge tries to prevent the host from sleeping. On macOS it uses `caffeinate`; on Linux it uses `systemd-inhibit` when available. Display lock or manual sleep can still interrupt agents depending on the OS. Set `SWARMFORGE_PREVENT_SLEEP=0` before `./swarm` to disable this behavior.

## Pack Cockpit

![SwarmForge dashboard](project-swarm.jpg)

The pack cockpit is a local web dashboard served from `main`'s scripts
(`pack_web`). It is the forge operator surface: several projects can run at
once. Chat talks to the **lieutenant**, who oversees the whole swarm, not to
a project agent.

Layout, top to bottom then left to right:

- **Header** — SwarmForge, live marker, **New Project**, **Open Project**, **Teardown**.
- **Attention** — human gates from every open project. Each row names the work as underlined **`project`/`task`** (project bold).
- **Board** — one band per open project, split by a horizontal bar. Each band has a header (**New Task**, **Close**) and that pack's swimlanes plus **Done**.
- **Work Queue** — the same project stack on the right; the two sides scroll independently.
- **Chat** — follow-ups to the lieutenant. Pending replies show live green `|` status under the request.

### Operating the dashboard

**New Project.** Name, mission (`mission.md` at the project top), pack radios,
editable conf. Check **github repo** and type `owner/repo` to clone first.
The directory is the last path segment. Existing names get an alert.

**Open Project.** Menu of directories under `projects/`. Opening refreshes
scripts from `packs/` (keeps `mission.md` and the project's conf) and starts
that pack. Already-open names get an alert.

**Start a task.** Click **New Task** on that project's header bar, give a
short stable **name** and the **task** text, then **OK**. That creates a card
in the project's master lane and queues a `(New Task)` note to that agent.

**Talk to the lieutenant.** Type in the chat composer (Enter sends,
Shift+Enter newline). The dashboard stores a durable request and injects
`[id] text` into the lieutenant pane. While the reply is pending, up to
two green `|` status lines appear under the request (same filtering as
card status) and replace each other as the lieutenant thinks. The chat
rail stays put unless the scroller is already at the bottom; then new
lines stay pinned to the bottom. The lieutenant is grok unless host
`swarmforge/swarmforge.conf` has a line like `Lieutenant grok --yolo`.

**Approve a specifier handoff.** When the specifier queues work for the next role, Attention shows **Approval**, the underlined **`project`/`task`** pair, a **Documents** menu for artifacts, **Approve**, and **Reject**. A new Attention row plays a short chime. Approve delivers the handoff and moves the card. Reject leaves the card with the specifier and notifies that agent. Two-pack has no specifier gate; those handoffs deliver immediately.

**Answer a clarification.** If an agent needs a human answer, Attention shows **Request clarification**, the question, and a text box. Submit injects the answer into that agent's pane. Do not use Approve/Reject for this.

**Watch the board.** Cards move when `handoffd` delivers a forward `git_handoff`. Click a card to open its task body in a resizable window. The card can show the agent's latest status sentence (the last pane line that contains `I'm`). Merge-only copies from `back-one` or `back-all` do not move the card. The last role in every pack sends the **terminal** handoff: `to:` every other role. That, not merely several names, moves the card to **Done**. The Done well is always on the board; it fills when that handoff is delivered.

**Inspect an agent.** Click a Work Queue role name, or **Open** in the header / chat rail, to pop a live pane capture. Those windows are growable. Agents themselves stay in tmux; these views do not replace the dashboard.

**Stop.** **Teardown** asks for confirmation, then kills the swarm. If the dashboard says **Swarm disconnected**, the UI is no longer talking to a live pack.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge/swarmforge.conf`
- Creates one tmux session per configured role
- Serves a **pack cockpit** in the browser and, by default on the pack branches, skips a Terminal window per role (`window-invisible`)
- Reads behavior from project-local `swarmforge/roles/<role>.prompt` files plus a layered `swarmforge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, `copilot`, or `grok`
- Puts the shared `swarmforge/scripts/` directory on each agent's `PATH`, including handoff helpers for active swarm communication
- Creates git worktrees under `.worktrees/` for roles assigned to dedicated worktree names
- Initializes a git repository in a new working directory when needed
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge/swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarmforge/roles/<role>.prompt` in the working tree being orchestrated.
- **Layered Constitution** — `swarmforge/constitution.prompt` directs agents to read article files under `swarmforge/constitution/articles/`.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, `copilot`, or `grok`.
- **Pack Cockpit** — A local dashboard for New Task, Attention, the board, Work Queue, master-agent chat, and Teardown.
- **Observable Swarm** — Watch agents from the dashboard; open a live pane when you need the raw session. Optional `window` lines still open a Terminal surface per role.
- **Self-Hosted & Lightweight** — Runs locally in tmux and a browser, with optional Terminal windows.

## Constitution Structure

Each runnable branch contains a `swarmforge/` directory with this general layout:

```text
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    articles/
      project.prompt
      local-engineering.prompt
      local-workflow.prompt
      ...
  roles/
    <role>.prompt
    ...
```

`constitution.prompt` is the entry point. Runnable branches normally use it to tell agents to read every file in `swarmforge/constitution/articles/`.

Shared default articles live on `main` under:

```text
swarmforge/constitution/articles/
  engineering.prompt
  handoffs.prompt
  workflow.prompt
```

`get-swarm-forge` always copies shared articles from `main` (or `SWARMFORGE_BASE_BRANCH`). Packs must not ship `engineering.prompt`, `workflow.prompt`, or `handoffs.prompt`. Those filenames are law from `main`.

Pack-specific additions and exceptions use explicit local filenames:

- `project.prompt` for the workflow's project shape and local topology.
- `local-engineering.prompt` for workflow-specific engineering rules.
- `local-workflow.prompt` for workflow-specific flow rules.

The `local-*.prompt` naming convention means "add to or specialize the shared default article for this pack." Use it for extra requirements, exceptions, or narrower instructions. Do not replace a shared article by committing the same filename.

For example, `main` provides `workflow.prompt`, while `six-pack` adds `local-workflow.prompt` for QA-specific handoff behavior.

## Roles

Each role in `swarmforge/swarmforge.conf` maps to a corresponding `swarmforge/roles/<role>.prompt` file.

## How It Works

In a runnable branch:

1. SwarmForge reads `swarmforge/swarmforge.conf`.
2. The project is already composed by `get-swarm-forge`: shared helper scripts and `engineering.prompt` / `workflow.prompt` / `handoffs.prompt` from `main`, plus pack-owned files (`swarm`, `swarmforge.conf`, role prompts, `constitution.prompt`, `project.prompt`, `local-*.prompt`). Shared article filenames are never taken from the pack.
3. Startup uses that composed `swarmforge/constitution/articles/` tree. Pack specialization is `local-*.prompt` and other pack-owned files, not a same-name override of a shared article.
4. Startup validates the configured role prompts, helper scripts, and terminal adapters.
5. If the target directory is not already a git repository, startup initializes one and creates the first commit.
6. Startup creates one git worktree per configured role under `.worktrees/`, unless the role is assigned to `master` or `none`.
7. Startup copies the composed `swarmforge/scripts/` and `swarmforge/constitution/` trees into each role worktree and puts that local scripts directory on each agent's `PATH`, so agents use local handoff helpers without reaching back into the master checkout.
8. SwarmForge creates tmux sessions, launches each configured backend in its assigned worktree, starts the pack dashboard, and opens a Terminal surface only for `window` (visible) roles.
9. Startup starts an OS-specific sleep inhibitor when one is available, and cleanup stops it with the swarm.
10. Roles communicate through daemon-delivered handoff files. Agents create validated drafts with `swarm_handoff.sh`, accept work with `ready_for_next.sh`, and complete work with `done_with_current.sh`.

## Handoff Protocol

Startup syncs the shared helper scripts into every role worktree under `swarmforge/scripts/` and puts that local directory on the agent's `PATH`. Agents do not send tmux messages directly. The launcher starts `handoffd.bb`, which owns tmux socket access, watches each agent outbox, copies validated handoff files into recipient inboxes, and sends only generic wake-up notifications.

Agents interact with handoffs through three helper scripts:

- `swarm_handoff.sh <draft-file>` validates outbound handoffs. Notes queue
  immediately; Git handoffs use the audit gate described below.
- `ready_for_next.sh` accepts work using the role's configured receive mode.
- `done_with_current.sh` completes the current task or batch using the role's configured receive mode.

Outbound drafts use one of two message types. A git handoff points the recipient at a committed state. The commit abbreviation must be exactly 10 hexadecimal characters; `swarm_handoff.sh` validates that it resolves to a single commit and canonicalizes it before queuing the handoff. The first valid Git handoff call returns `AUDIT_REQUIRED` without queueing or completing the sender's current inbox item, and increments the task card's audit counter. The sender must re-read the complete task and referenced sources, trace every requirement and constraint to role-appropriate work and evidence, examine boundaries and failure cases, fix every finding, rerun applicable checks, and repeat the audit. Only an unchanged second call queues the handoff without another increment, after which any required approval is requested. A changed draft, task, sender, recipient set, or commit invalidates the earlier audit and creates a new counted challenge.

```text
type: git_handoff
to: <role>[,<role>...]
priority: NN
task: <short-stable-task-name>
commit: <10-character-commit-abbrev>
```

A note is one short freeform message:

```text
type: note
to: <role>[,<role>...]
priority: NN
message: <one line, max 80 chars>
```

The helper generates the delivered payload. Agents do not write long handoff bodies, branch names, queue filenames, or tmux commands. If the sender's conf has `back-one` or `back-all`, the helper also writes the merge-only copies; agents do not list those earlier roles on `to:`.

Recipient agents run `ready_for_next.sh` when notified or after restart. It dispatches to the task or batch helper configured for that role. If it prints `NO_TASK`, they stop waiting for work. If it prints `TASK: <path>`, they treat the printed `TASK_NAME` and `PAYLOAD` as the task. If it prints `BATCH: <path>`, they process the printed `BATCH_ITEM` entries in helper-delivered order. If a wake-up arrives while an agent is already working, it can ignore the wake-up. `done_with_current.sh` completes the current item only: it prints `MAIL_WAITING` when more mail is queued, or `NO_TASK`. The agent then runs `ready_for_next.sh` if mail is waiting.

The durable handoff files and lifecycle headers replace the old logbook and resend queue. Runtime handoff state lives under `.swarmforge/handoffs/` in each worktree, with `outbox`, `sent`, `failed`, and `inbox` subdirectories. Agents should not hand-edit, merge, stage, or commit handoff runtime state. See [swarmforge/handoff-protocol.md](swarmforge/handoff-protocol.md) for the full protocol.

## The `swarmforge.conf` File

`swarmforge/swarmforge.conf` defines the swarm window-by-window. Each line has this form:

```conf
window-invisible <role> <agent> <worktree> [task|batch] [forward-only|back-one|back-all] [extra-cli-args...]
window <role> <agent> <worktree> [task|batch] [forward-only|back-one|back-all] [extra-cli-args...]
```

`window-invisible` starts the agent in tmux without a Terminal window (the pack default). `window` also opens a Terminal surface for that role.

The optional receive mode defaults to `task`. Use `batch` for roles that should consume all currently queued equal-priority handoffs as one batch.

The optional propagation token defaults to `forward-only`. The card still follows the forward send to the next window.

- `forward-only` — no extra copies.
- `back-one` — also queue a merge-only copy to the previous window.
- `back-all` — also queue merge-only copies to every earlier window.

Those extra copies do not move the card. The recipient merges the copy and keeps working; it does not hand that copy onward. The card goes Done only when the last window sends a `git_handoff`.

The **host** conf may include a lieutenant line instead of windows:

```conf
Lieutenant grok --yolo
```

If that line is omitted, the lieutenant is grok with no extra args.

Pack defaults (roles not listed here are `forward-only`):

- `two-pack`: coder grok, cleaner codex `batch back-one`
- `four-pack`: specifier codex, coder grok, refactorer grok, architect
  codex `batch back-all`; refactorer `back-one`
- `six-pack`: specifier codex, coder grok, cleaner grok `batch back-one`,
  architect grok `batch back-all`, hardender codex, QA grok `batch back-all`

Any fields after receive-mode and the propagation token are passed directly to the agent CLI as additional arguments. If you omit those tokens, extra arguments may start at the fifth field:

```conf
window coder copilot wt-coder --yolo
window architect claude wt-arch task --dangerously-skip-permissions
```

You can define as many windows as your project needs. Each `role` maps to a corresponding prompt file at `swarmforge/roles/<role>.prompt`, so a config containing `architect`, `coder`, `reviewer`, `research`, and `release` windows would expect:

- `swarmforge/roles/architect.prompt`
- `swarmforge/roles/coder.prompt`
- `swarmforge/roles/reviewer.prompt`
- `swarmforge/roles/research.prompt`
- `swarmforge/roles/release.prompt`

This lets each project choose its own swarm shape instead of being locked to a fixed set of roles.

Example config (four-pack shape, pack default is invisible):

```conf
window-invisible specifier codex master --yolo
window-invisible coder grok coder
window-invisible refactorer grok refactorer back-one
window-invisible architect codex architect batch back-all --yolo
```

In the example above, the agents run in these worktrees:

- `specifier` -> main working directory on `master` (master agent: New Task and chat)
- `coder` -> `.worktrees/coder`
- `refactorer` -> `.worktrees/refactorer`
- `architect` -> `.worktrees/architect`

If a window uses `master` as its worktree name, SwarmForge does not create `.worktrees/master`; that role runs in the main working directory on the `master` branch.

## tmux Behavior

SwarmForge uses a project-specific tmux socket recorded in `.swarmforge/tmux-socket`, so each project swarm is isolated from other tmux sessions. It also honors tmux `base-index` and `pane-base-index` settings when launching agents and sending notifications, so configurations that number windows or panes from `1` work without requiring users to change their tmux preferences.

## Terminal Behavior

Pack branches use `window-invisible`, so this adapter does not open a window per role. Visible `window` lines still open trackable terminal windows or tabs through a small terminal backend adapter.

Default detection:

- If AppleScript is available, SwarmForge opens macOS Terminal.app windows.
- Otherwise, if `wt.exe` is available, SwarmForge opens Windows Terminal windows.
- Otherwise, SwarmForge attaches the cleanup tmux session in the current shell.

After copying a runnable branch, set `SWARMFORGE_TERMINAL` to override detection:

```sh
SWARMFORGE_TERMINAL=ghostty ./swarm
SWARMFORGE_TERMINAL=terminal-app ./swarm
SWARMFORGE_TERMINAL=windows-terminal ./swarm
SWARMFORGE_TERMINAL=none ./swarm
```

Use `ghostty` when you want SwarmForge to open Ghostty tabs instead of the default Terminal.app windows. Use `windows-terminal` when you want SwarmForge to open Windows Terminal windows from WSL. Use `none` when you want SwarmForge to skip terminal automation and attach the cleanup tmux session in the current shell.

### Adding A Terminal Backend

The shared terminal backends are carried on `main` under `swarmforge/scripts/terminal-adapters/`. Runnable branches copy those scripts at startup. To add a new backend, update `main` by creating one file named after the backend:

```text
swarmforge/scripts/terminal-adapters/wezterm.sh
```

The file must define this small contract:

```sh
terminal_backend_label() {
  echo "WezTerm"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"

  # Open a terminal surface that runs:
  # cd "$WORKING_DIR" && exec tmux -S "$TMUX_SOCKET" attach-session -t "$session"
  #
  # Print a stable window/tab id to stdout.
}

terminal_window_exists() {
  local window_id="$1"

  # Return 0 if the id from terminal_open_session still exists.
  # Return nonzero otherwise.
}

terminal_close_window() {
  local window_id="$1"

  # Close the id from terminal_open_session.
}
```

If the terminal can open sessions but cannot return stable ids for open/check/close, keep `terminal_backend_can_open_sessions` as `return 0` and set `terminal_backend_tracks_windows` to `return 1`. SwarmForge will open one surface per session and skip the watchdog for that backend. `swarmforge/scripts/terminal-adapters/windows-terminal.sh` is an example of this launch-only style.

If the backend cannot open sessions at all, set both capability functions to `return 1`; SwarmForge will attach the cleanup tmux session in the current shell. Only edit `swarmforge/scripts/swarm-terminal-adapter.sh` when adding aliases or changing default auto-detection.

## Window Behavior

The usual shutdown path for a pack is **Teardown** on the dashboard, not closing a Terminal window.

If you use visible `window` lines, each agent window is attached to a tmux session. Terminal selection, copy, and paste may follow tmux and terminal-emulator rules rather than ordinary text-field behavior. If copy or paste feels unusual, check whether tmux copy mode is active before assuming the agent is stuck.

The first **visible** window in `swarmforge.conf` is the cleanup window. Closing that window shuts down tmux sessions, remaining tracked windows, and the swarm.

Closing any other tracked window is non-destructive. The watchdog reopens that window and attaches it back to the same tmux session, so the agent state and terminal history remain intact. This is often the simplest way to recover a window that has landed in an unfamiliar tmux mode or otherwise feels stuck.
