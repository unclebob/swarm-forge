#!/usr/bin/env python3
"""Interactive coding agent for OpenAI-compatible local servers."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


MAX_FILE_BYTES = 200_000
MAX_TOOL_OUTPUT = 20_000


def fail(message: str) -> None:
    print(f"local-agent: {message}", file=sys.stderr)


def read_key_value_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"{path}:{line_number}: expected key=value")
        key, value = line.split("=", 1)
        values[key.strip().lower().replace("-", "_")] = value.strip()
    return values


def env_or_file(values: dict[str, str], key: str, default: str = "") -> str:
    return os.environ.get(f"SWARMFORGE_LOCAL_{key.upper()}", values.get(key, default))


def versioned_url(base_url: str, suffix: str) -> str:
    base = base_url.rstrip("/")
    if not base.endswith("/v1"):
        base += "/v1"
    return f"{base}/{suffix}"


def http_json(url: str, payload: dict[str, Any] | None, api_key: str, timeout: int) -> dict[str, Any]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(
        url,
        data=body,
        headers=headers,
        method="POST" if body is not None else "GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code} from {url}: {detail[:1000]}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"cannot reach {url}: {error.reason}") from error


def path_inside(root: Path, raw_path: str, *, writable: bool = False) -> Path:
    candidate = (root / raw_path).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as error:
        raise ValueError(f"path escapes the worktree: {raw_path}") from error
    if writable:
        relative_parts = candidate.relative_to(root).parts
        if relative_parts and relative_parts[0] in {".git", ".swarmforge", ".worktrees"}:
            raise ValueError(f"refusing to write runtime path: {raw_path}")
    return candidate


def clipped(value: str) -> str:
    if len(value) <= MAX_TOOL_OUTPUT:
        return value
    return value[:MAX_TOOL_OUTPUT] + "\n[output clipped]"


def tool_list_files(root: Path, arguments: dict[str, Any]) -> str:
    directory = path_inside(root, str(arguments.get("path", ".")))
    if not directory.is_dir():
        raise ValueError(f"not a directory: {arguments.get('path', '.')}")
    recursive = bool(arguments.get("recursive", False))
    entries: list[str] = []
    iterator = directory.rglob("*") if recursive else directory.iterdir()
    for entry in iterator:
        relative = entry.relative_to(root)
        if any(part in {".git", ".swarmforge", ".worktrees", "__pycache__"} for part in relative.parts):
            continue
        entries.append(str(relative))
        if len(entries) >= 2000:
            break
    return "\n".join(sorted(entries)) or "(empty)"


def tool_read_file(root: Path, arguments: dict[str, Any]) -> str:
    path = path_inside(root, str(arguments["path"]))
    if not path.is_file():
        raise ValueError(f"not a file: {arguments['path']}")
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError(f"file is larger than {MAX_FILE_BYTES} bytes: {arguments['path']}")
    return path.read_text(encoding="utf-8", errors="replace")


def tool_write_file(root: Path, arguments: dict[str, Any]) -> str:
    path = path_inside(root, str(arguments["path"]), writable=True)
    content = str(arguments.get("content", ""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return f"wrote {path.relative_to(root)} ({len(content)} characters)"


def tool_run_command(root: Path, arguments: dict[str, Any], auto_approve: bool) -> str:
    command = str(arguments["command"])
    if not auto_approve:
        answer = input(f"\nAllow local agent to run `{command}` in {root}? [y/N] ").strip().lower()
        if answer not in {"y", "yes"}:
            return "command denied by user"
    timeout = max(1, min(int(arguments.get("timeout", 120)), 600))
    completed = subprocess.run(
        command,
        cwd=root,
        shell=True,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    output = (completed.stdout + completed.stderr).strip()
    return clipped(f"exit_code={completed.returncode}\n{output}")


TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List files in the project worktree.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Relative directory, default ."},
                    "recursive": {"type": "boolean"},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read a UTF-8 text file in the project worktree.",
            "parameters": {
                "type": "object",
                "required": ["path"],
                "properties": {"path": {"type": "string"}},
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Create or replace a UTF-8 text file in the project worktree.",
            "parameters": {
                "type": "object",
                "required": ["path", "content"],
                "properties": {
                    "path": {"type": "string"},
                    "content": {"type": "string"},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_command",
            "description": "Run a project command such as a test or formatter.",
            "parameters": {
                "type": "object",
                "required": ["command"],
                "properties": {
                    "command": {"type": "string"},
                    "timeout": {"type": "integer"},
                },
            },
        },
    },
]


def execute_tool(root: Path, name: str, arguments: dict[str, Any], auto_approve: bool) -> str:
    if name == "list_files":
        return tool_list_files(root, arguments)
    if name == "read_file":
        return tool_read_file(root, arguments)
    if name == "write_file":
        return tool_write_file(root, arguments)
    if name == "run_command":
        return tool_run_command(root, arguments, auto_approve)
    raise ValueError(f"unknown tool: {name}")


def message_text(message: dict[str, Any]) -> str:
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


def complete(messages: list[dict[str, Any]], config: dict[str, Any]) -> dict[str, Any]:
    payload = {
        "model": config["model"],
        "messages": messages,
        "temperature": config["temperature"],
        "max_tokens": config["max_tokens"],
        "tools": TOOLS,
        "tool_choice": "auto",
        "stream": False,
    }
    return http_json(
        versioned_url(config["base_url"], "chat/completions"),
        payload,
        config["api_key"],
        config["timeout"],
    )


def run_turn(messages: list[dict[str, Any]], root: Path, config: dict[str, Any]) -> None:
    for _ in range(20):
        response = complete(messages, config)
        choices = response.get("choices") or []
        if not choices:
            raise RuntimeError(f"server response has no choices: {json.dumps(response)[:1000]}")
        assistant = choices[0].get("message") or {}
        messages.append(assistant)
        calls = assistant.get("tool_calls") or []
        if not calls:
            print(message_text(assistant) or "(model returned no text)")
            return
        for call in calls:
            function = call.get("function") or {}
            name = function.get("name", "")
            try:
                arguments = json.loads(function.get("arguments", "{}"))
                result = execute_tool(root, name, arguments, config["auto_approve"])
            except (KeyError, ValueError, TypeError, json.JSONDecodeError, subprocess.SubprocessError) as error:
                result = f"tool error: {error}"
            messages.append({
                "role": "tool",
                "tool_call_id": call.get("id", name),
                "name": name,
                "content": clipped(result),
            })
    raise RuntimeError("tool-call loop exceeded 20 iterations")


def build_config(args: argparse.Namespace, file_values: dict[str, str]) -> dict[str, Any]:
    base_url = args.base_url or env_or_file(file_values, "base_url")
    if not base_url:
        host = args.host or env_or_file(file_values, "host", "127.0.0.1")
        port = args.port or env_or_file(file_values, "port", "1234")
        base_url = f"http://{host}:{port}/v1"
    model = args.model or env_or_file(file_values, "model")
    api_key = args.api_key or env_or_file(file_values, "api_key")
    timeout = args.timeout if args.timeout is not None else int(env_or_file(file_values, "timeout", "120"))
    max_tokens = args.max_tokens if args.max_tokens is not None else int(env_or_file(file_values, "max_tokens", "4096"))
    temperature = args.temperature if args.temperature is not None else float(env_or_file(file_values, "temperature", "0.2"))
    if not model:
        models = http_json(versioned_url(base_url, "models"), None, api_key, timeout)
        data = models.get("data") or []
        if not data or not data[0].get("id"):
            raise RuntimeError("no model configured and /v1/models returned no model id")
        model = data[0]["id"]
    return {
        "base_url": base_url,
        "model": model,
        "api_key": api_key,
        "timeout": timeout,
        "max_tokens": max_tokens,
        "temperature": temperature,
        "auto_approve": args.auto_approve or env_or_file(file_values, "auto_approve", "false").lower() in {"1", "true", "yes"},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="SwarmForge OpenAI-compatible local coding agent")
    parser.add_argument("--cwd", required=True)
    parser.add_argument("--prompt-file", required=True)
    parser.add_argument("--config-file", default="")
    parser.add_argument("--base-url", default="")
    parser.add_argument("--host", default="")
    parser.add_argument("--port", default="")
    parser.add_argument("--model", default="")
    parser.add_argument("--api-key", default="")
    parser.add_argument("--timeout", type=int, default=None)
    parser.add_argument("--max-tokens", type=int, default=None)
    parser.add_argument("--temperature", type=float, default=None)
    parser.add_argument("--auto-approve", action="store_true")
    args = parser.parse_args()

    root = Path(args.cwd).resolve()
    config_path = Path(args.config_file).resolve() if args.config_file else root / "swarmforge" / "local-agent.conf"
    try:
        file_values = read_key_value_file(config_path)
        config = build_config(args, file_values)
        instructions = Path(args.prompt_file).read_text(encoding="utf-8")
        messages: list[dict[str, Any]] = [{"role": "system", "content": instructions}]
    except (OSError, ValueError, RuntimeError) as error:
        fail(str(error))
        return 1

    print(f"SwarmForge local agent | {config['base_url']} | model={config['model']}")
    print(f"Worktree: {root}")
    print("Type /help for commands; Ctrl-D or /quit exits.\n")
    while True:
        try:
            user_input = input("local-agent> ")
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if not user_input.strip():
            continue
        if user_input.strip() in {"/quit", "/exit"}:
            return 0
        if user_input.strip() == "/help":
            print("Escribe una tarea para el agente. /reset reinicia la conversación; /quit sale.")
            continue
        if user_input.strip() == "/reset":
            messages = [{"role": "system", "content": instructions}]
            print("Conversación reiniciada.")
            continue
        messages.append({"role": "user", "content": user_input})
        try:
            run_turn(messages, root, config)
        except (RuntimeError, OSError, ValueError) as error:
            fail(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
