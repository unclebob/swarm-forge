# Local OpenAI-compatible backend

SwarmForge can launch a `local` agent backend that talks to a local HTTP
server exposing the OpenAI-compatible Chat Completions API. This works with
llama.cpp `llama-server`, LM Studio, and other compatible servers.

The backend is `scripts/swarmforge-local-agent.py` and uses only Python's
standard library. It supports four project tools: listing files, reading
files, writing files, and running project commands. Command execution asks
for confirmation unless `auto_approve=true` is explicitly configured.

## Configuration

Copy `local-agent.conf.example` to the orchestrated project's
`swarmforge/local-agent.conf`:

```text
base_url=http://127.0.0.1:1234/v1
model=
api_key=
timeout=120
max_tokens=4096
temperature=0.2
auto_approve=false
```

Alternatively, set `SWARMFORGE_LOCAL_BASE_URL`,
`SWARMFORGE_LOCAL_HOST`, `SWARMFORGE_LOCAL_PORT`,
`SWARMFORGE_LOCAL_MODEL`, and `SWARMFORGE_LOCAL_API_KEY`. Command-line
arguments passed after the receive mode take precedence over the file and
environment values.

Use `local` as the agent in `swarmforge/swarmforge.conf`, for example:

```text
window specifier local master
window coder local coder
window refactorer local refactorer
window architect local architect batch
```

The model must support tool calling for autonomous file edits. For llama.cpp,
start `llama-server` with a compatible chat template and `--jinja` when
needed. LM Studio must have its local server enabled and a tool-capable model
loaded. If the model does not emit tool calls, the backend still supports
interactive chat but cannot modify the worktree autonomously.

The first time the backend starts without `model=`, it selects the first model
returned by `/v1/models`.
