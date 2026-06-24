#!/usr/bin/env python3
"""
Extract structured data from a Claude Code session transcript (JSONL).

Usage:
    python extract.py <session-jsonl-path> [--subagents-dir <path>] [--summary] [--metadata-only]

Outputs JSON to stdout. Use --summary for a compact version that omits
individual tool call details (just counts and key events).
Use --metadata-only for cheap session verification (head/tail read only).
"""

import json
import sys
import os
import glob
from collections import Counter, defaultdict
from pathlib import Path
from datetime import datetime

# Approximate pricing per million tokens, by model family.
# Update these when Anthropic changes pricing and bump PRICING_LAST_VERIFIED.
# cache_create = 1.25x input (5-minute TTL); cache_read = 0.1x input.
PRICING_LAST_VERIFIED = "2026-06-14"
PRICE_TABLE = {
    "opus":   {"input": 5.0,  "output": 25.0, "cache_create": 6.25, "cache_read": 0.50},
    "sonnet": {"input": 3.0,  "output": 15.0, "cache_create": 3.75, "cache_read": 0.30},
    "haiku":  {"input": 1.0,  "output": 5.0,  "cache_create": 1.25, "cache_read": 0.10},
    "fable":  {"input": 10.0, "output": 50.0, "cache_create": 12.5, "cache_read": 1.00},
}
# Fall back to the most expensive family for an unknown/empty model so cost is
# never silently understated.
DEFAULT_PRICE_FAMILY = "opus"


def price_for_model(model):
    """Map a model id/name to its pricing family. Unknown models fall back to
    DEFAULT_PRICE_FAMILY."""
    m = (model or "").lower()
    for family in ("haiku", "sonnet", "opus", "fable"):
        if family in m:
            return PRICE_TABLE[family]
    return PRICE_TABLE[DEFAULT_PRICE_FAMILY]


def compute_cost(tokens, model):
    """Cost in USD for a token-usage dict, priced for the given model."""
    p = price_for_model(model)
    return (
        tokens["input_tokens"] / 1_000_000 * p["input"]
        + tokens["output_tokens"] / 1_000_000 * p["output"]
        + tokens["cache_creation_input_tokens"] / 1_000_000 * p["cache_create"]
        + tokens["cache_read_input_tokens"] / 1_000_000 * p["cache_read"]
    )

SCHEMA_VERSION = "0.1.0"

# Head/tail buffer size for lite reads (matches Claude Code's LITE_READ_BUF_SIZE)
LITE_READ_BUF_SIZE = 65536


def stream_jsonl(path):
    """Yield parsed records one at a time without loading the full file."""
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    continue


def read_head_tail(path):
    """Read first and last 64KB of a file. Returns (head_str, tail_str, file_size)."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        head_bytes = f.read(LITE_READ_BUF_SIZE)
        head = head_bytes.decode("utf-8", errors="replace")

        if size <= LITE_READ_BUF_SIZE:
            return head, head, size

        f.seek(max(0, size - LITE_READ_BUF_SIZE))
        tail_bytes = f.read(LITE_READ_BUF_SIZE)
        tail = tail_bytes.decode("utf-8", errors="replace")

    return head, tail, size


def extract_json_field(text, key):
    """Extract a JSON string field value without full parsing (regex-free).
    Matches '"key":"value"' or '"key": "value"' patterns."""
    for pattern in [f'"{key}":"', f'"{key}": "']:
        idx = text.find(pattern)
        if idx < 0:
            continue
        start = idx + len(pattern)
        i = start
        while i < len(text):
            if text[i] == "\\":
                i += 2
                continue
            if text[i] == '"':
                return text[start:i]
            i += 1
    return None


def extract_metadata_lite(path):
    """Extract session metadata from head/tail only — no full parse.
    Used for session verification and discovery."""
    head, tail, size = read_head_tail(path)

    # Extract from head (start of session)
    session_id = extract_json_field(head, "sessionId")
    cwd = extract_json_field(head, "cwd")
    git_branch = extract_json_field(head, "gitBranch")
    version = extract_json_field(head, "version")
    start_time = extract_json_field(head, "timestamp")

    # Extract from tail (end of session). When the file exceeds the buffer the
    # tail starts mid-line, so the first split element is a partial record whose
    # timestamp would be wrong — drop it before scanning. Scan complete lines
    # backwards for the last timestamp.
    tail_lines = tail.split("\n")
    if size > LITE_READ_BUF_SIZE and tail_lines:
        tail_lines = tail_lines[1:]
    end_time = None
    for line in reversed(tail_lines):
        ts = extract_json_field(line, "timestamp")
        if ts:
            end_time = ts
            break

    # First user message for verification
    first_prompt = None
    for line in head.split("\n"):
        if '"role":"user"' not in line and '"role": "user"' not in line:
            continue
        if '"tool_result"' in line:
            continue
        # Try to extract text content
        text = extract_json_field(line, "text")
        if text and not text.startswith("<system-reminder>"):
            first_prompt = text[:200]
            break

    duration_seconds = None
    if start_time and end_time:
        start = parse_ts(start_time)
        end = parse_ts(end_time)
        if start and end:
            duration_seconds = round((end - start).total_seconds())

    return {
        "session_id": session_id,
        "cwd": cwd,
        "git_branch": git_branch,
        "version": version,
        "start_time": start_time,
        "end_time": end_time,
        "duration_seconds": duration_seconds,
        "file_size_bytes": size,
        "first_prompt": first_prompt,
    }


def parse_ts(ts_str):
    """Parse ISO 8601 timestamp string to datetime."""
    if not ts_str:
        return None
    try:
        return datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None


def extract_all_streaming(jsonl_path, subagents_dir=None, summary_mode=False):
    """Main extraction pipeline using streaming — processes line-by-line."""

    # Session metadata
    session = {
        "session_id": None,
        "cwd": None,
        "git_branch": None,
        "version": None,
        "start_time": None,
        "end_time": None,
        "duration_seconds": None,
        "branches_seen": set(),
        "model": None,
    }

    # Token totals
    tokens_total = {
        "input_tokens": 0,
        "output_tokens": 0,
        "cache_creation_input_tokens": 0,
        "cache_read_input_tokens": 0,
    }
    turn_count = 0

    # Tool tracking
    tool_calls = []
    tool_counts = Counter()
    total_tool_calls = 0

    # Tool result sizes (tool_use_id -> size in bytes)
    tool_result_sizes = {}

    # Conversation arc
    arc = []

    # Git tracking
    branches = set()
    commits = []
    prs = []

    # File tracking
    files = defaultdict(set)

    for rec in stream_jsonl(jsonl_path):
        # --- Session metadata ---
        if rec.get("sessionId") and not session["session_id"]:
            session["session_id"] = rec["sessionId"]
        if rec.get("cwd") and not session["cwd"]:
            session["cwd"] = rec["cwd"]
        if rec.get("gitBranch"):
            if not session["git_branch"]:
                session["git_branch"] = rec["gitBranch"]
            session["branches_seen"].add(rec["gitBranch"])
            branches.add(rec["gitBranch"])
        if rec.get("version") and not session["version"]:
            session["version"] = rec["version"]

        ts = rec.get("timestamp")
        if ts:
            if not session["start_time"]:
                session["start_time"] = ts
            session["end_time"] = ts

        msg = rec.get("message", {})
        role = msg.get("role")
        content = msg.get("content", "")
        usage = msg.get("usage", {})

        # --- Token usage (assistant messages only) ---
        if usage and role == "assistant":
            tokens_total["input_tokens"] += usage.get("input_tokens", 0)
            tokens_total["output_tokens"] += usage.get("output_tokens", 0)
            tokens_total["cache_creation_input_tokens"] += usage.get("cache_creation_input_tokens", 0)
            tokens_total["cache_read_input_tokens"] += usage.get("cache_read_input_tokens", 0)
            turn_count += 1
            if not session["model"] and msg.get("model"):
                session["model"] = msg.get("model")

        # --- Process content blocks ---
        if isinstance(content, list):
            for block in content:
                if not isinstance(block, dict):
                    continue

                block_type = block.get("type")

                # Tool use blocks (assistant calling tools)
                if block_type == "tool_use":
                    name = block.get("name", "unknown")
                    tool_input = block.get("input", {})
                    tool_counts[name] += 1
                    total_tool_calls += 1

                    call_summary = {
                        "name": name,
                        "timestamp": ts,
                        "tool_use_id": block.get("id", ""),
                    }

                    if name == "Agent":
                        call_summary["agent_description"] = tool_input.get("description", "")
                        call_summary["agent_type"] = tool_input.get("subagent_type", "")
                        call_summary["agent_model"] = tool_input.get("model", "")
                        call_summary["agent_prompt_preview"] = tool_input.get("prompt", "")[:300]
                        call_summary["run_in_background"] = tool_input.get("run_in_background", False)
                    elif name == "Skill":
                        call_summary["skill_name"] = tool_input.get("skill", "")
                        call_summary["skill_args"] = tool_input.get("args", "")
                    elif name == "Bash":
                        call_summary["command"] = tool_input.get("command", "")[:300]
                    elif name in ("Read", "Write", "Edit"):
                        call_summary["file_path"] = tool_input.get("file_path", "")
                    elif name in ("Grep", "Glob"):
                        call_summary["pattern"] = tool_input.get("pattern", "")
                    elif name in ("TaskCreate", "TaskUpdate", "TaskList", "TaskOutput"):
                        call_summary["task_detail"] = {
                            k: v for k, v in tool_input.items()
                            if k in ("description", "status", "id")
                        }
                    elif name == "AskUserQuestion":
                        questions = tool_input.get("questions", [])
                        call_summary["questions"] = [q.get("question", "") for q in questions]
                    elif name.startswith("mcp__"):
                        call_summary["mcp_inputs_preview"] = json.dumps(tool_input)[:300]

                    tool_calls.append(call_summary)

                    # Track files
                    fp = tool_input.get("file_path", "")
                    if fp:
                        if name == "Read":
                            files["read"].add(fp)
                        elif name == "Write":
                            files["written"].add(fp)
                        elif name == "Edit":
                            files["edited"].add(fp)

                    # Track git activity from bash commands
                    if name == "Bash":
                        cmd = tool_input.get("command", "")
                        if "git commit" in cmd:
                            commits.append({"command": cmd[:200], "timestamp": ts})
                        if "gh pr" in cmd:
                            prs.append({"command": cmd[:200], "timestamp": ts})

                # Tool result blocks — capture SIZE only, not content
                elif block_type == "tool_result":
                    tool_use_id = block.get("tool_use_id", "")
                    result_content = block.get("content", "")
                    if isinstance(result_content, str):
                        size_bytes = len(result_content.encode("utf-8", errors="replace"))
                    elif isinstance(result_content, list):
                        # Multi-block results (e.g., images + text)
                        size_bytes = 0
                        for rb in result_content:
                            if isinstance(rb, dict):
                                text = rb.get("text", "")
                                if text:
                                    size_bytes += len(text.encode("utf-8", errors="replace"))
                                # Image/binary blocks — estimate from base64 if present
                                data = rb.get("data", "")
                                if data:
                                    size_bytes += len(data)
                            elif isinstance(rb, str):
                                size_bytes += len(rb.encode("utf-8", errors="replace"))
                    else:
                        size_bytes = len(json.dumps(result_content).encode("utf-8"))

                    if tool_use_id:
                        tool_result_sizes[tool_use_id] = size_bytes

                # Text blocks — conversation arc (both assistant AND user)
                elif block_type == "text":
                    text = block.get("text", "").strip()
                    if role == "assistant" and text and len(text) > 20:
                        arc.append({
                            "role": "assistant",
                            "text": text[:1000],
                            "timestamp": ts,
                        })

            # After processing all blocks in a list-format user message,
            # collect text blocks into the arc
            if role == "user" and isinstance(content, list):
                user_text = ""
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "text":
                        user_text += block.get("text", "")
                    elif isinstance(block, str):
                        user_text += block
                user_text = user_text.strip()
                if user_text and not user_text.startswith("<system-reminder>"):
                    arc.append({
                        "role": "user",
                        "text": user_text[:2000],
                        "timestamp": ts,
                    })

        # User messages with string content (simple format)
        elif role == "user":
            text = ""
            if isinstance(content, str):
                text = content
            text = text.strip()
            if text and not text.startswith("<system-reminder>"):
                arc.append({
                    "role": "user",
                    "text": text[:2000],
                    "timestamp": ts,
                })

    # --- Post-processing ---

    # Compute duration
    if session["start_time"] and session["end_time"]:
        start = parse_ts(session["start_time"])
        end = parse_ts(session["end_time"])
        if start and end:
            session["duration_seconds"] = round((end - start).total_seconds())
    session["branches_seen"] = sorted(session["branches_seen"])

    # Compute cost (priced for the session's own model)
    cost = compute_cost(tokens_total, session["model"])

    # Attach result sizes to tool calls
    for call in tool_calls:
        tid = call.get("tool_use_id", "")
        if tid in tool_result_sizes:
            call["result_size_bytes"] = tool_result_sizes[tid]

    # Compute tool result size stats
    result_size_stats = {}
    if tool_result_sizes:
        sizes_by_tool = defaultdict(list)
        for call in tool_calls:
            if "result_size_bytes" in call:
                sizes_by_tool[call["name"]].append(call["result_size_bytes"])

        for tool_name, sizes in sorted(sizes_by_tool.items(), key=lambda x: -sum(x[1])):
            result_size_stats[tool_name] = {
                "count": len(sizes),
                "total_bytes": sum(sizes),
                "avg_bytes": round(sum(sizes) / len(sizes)),
                "max_bytes": max(sizes),
            }

    # Extract agents
    agents = _extract_agents(tool_calls, subagents_dir)

    # Extract skills
    skills = [
        {"name": c.get("skill_name", ""), "args": c.get("skill_args", ""), "timestamp": c.get("timestamp")}
        for c in tool_calls if c["name"] == "Skill"
    ]

    # Warn if agents exist but have no cost data (subagents_dir missing)
    agents_without_cost = [a for a in agents if a.get("estimated_cost_usd") is None
                           and a.get("description") and not a.get("description", "").startswith("[unmatched")]
    if agents_without_cost:
        print(f"Warning: {len(agents_without_cost)} agent dispatch(es) have no subagent cost data. "
              f"Pass --subagents-dir <path> to attribute subagent costs.",
              file=sys.stderr)

    # Build result
    result = {
        "schema_version": SCHEMA_VERSION,
        "session": session,
        "tokens": {
            "total": tokens_total,
            "turn_count": turn_count,
            "estimated_cost_usd": round(cost, 4),
        },
        "agents": agents,
        "skills": skills,
        "git": {
            "branches": sorted(branches),
            "commits": commits,
            "pr_operations": prs,
        },
        "files": {k: sorted(v) for k, v in files.items()},
        "conversation_arc": arc,
        "tool_result_sizes": result_size_stats,
    }

    if summary_mode:
        result["tools"] = {
            "counts": dict(tool_counts.most_common()),
            "total_calls": total_tool_calls,
        }
    else:
        result["tools"] = {
            "calls": tool_calls,
            "counts": dict(tool_counts.most_common()),
            "total_calls": total_tool_calls,
        }

    return result


def _extract_agents(tool_calls, subagents_dir=None):
    """Extract agent dispatch details and match with subagent JSONL files."""
    agents = []
    for call in tool_calls:
        if call["name"] == "Agent":
            agent = {
                "description": call.get("agent_description", ""),
                "type": call.get("agent_type", "") or "general-purpose",
                "model": call.get("agent_model", "") or "inherited",
                "prompt_preview": call.get("agent_prompt_preview", ""),
                "background": call.get("run_in_background", False),
                "timestamp": call.get("timestamp"),
                "tool_use_id": call.get("tool_use_id", ""),
                "tokens": None,
                "estimated_cost_usd": None,
            }
            if "result_size_bytes" in call:
                agent["result_size_bytes"] = call["result_size_bytes"]
            agents.append(agent)

    if subagents_dir and os.path.isdir(subagents_dir):
        _match_subagent_files(agents, subagents_dir)

    return agents


def _match_subagent_files(agents, subagents_dir):
    """Match subagent JSONL files to dispatches using timestamp proximity."""
    subagent_files = sorted(glob.glob(os.path.join(subagents_dir, "*.jsonl")))
    MAX_MATCH_WINDOW_S = 60

    subagent_info = []
    for sa_file in subagent_files:
        sa_tokens = {"input_tokens": 0, "output_tokens": 0,
                     "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}
        sa_start = None
        sa_model = None
        turn_count = 0

        for rec in stream_jsonl(sa_file):
            msg = rec.get("message", {})
            usage = msg.get("usage", {})
            if usage and msg.get("role") == "assistant":
                sa_tokens["input_tokens"] += usage.get("input_tokens", 0)
                sa_tokens["output_tokens"] += usage.get("output_tokens", 0)
                sa_tokens["cache_creation_input_tokens"] += usage.get("cache_creation_input_tokens", 0)
                sa_tokens["cache_read_input_tokens"] += usage.get("cache_read_input_tokens", 0)
                turn_count += 1
                if sa_model is None and msg.get("model"):
                    sa_model = msg.get("model")
            if sa_start is None and "timestamp" in rec:
                sa_start = parse_ts(rec["timestamp"])

        # Price each subagent for the model it actually ran on.
        sa_cost = compute_cost(sa_tokens, sa_model)

        # Load meta file if present
        meta = None
        meta_file = sa_file.replace(".jsonl", ".meta.json")
        if os.path.exists(meta_file):
            with open(meta_file) as f:
                meta = json.load(f)

        subagent_info.append({
            "file": os.path.basename(sa_file),
            "tokens": sa_tokens,
            "cost": round(sa_cost, 4),
            "start_time": sa_start,
            "model": sa_model,
            "meta": meta,
        })

    # Match by timestamp proximity
    matched_dispatches = set()
    matched_subagents = set()

    for sa_idx, sa in enumerate(subagent_info):
        if not sa["start_time"]:
            continue
        best_match = None
        best_delta = None

        for ag_idx, agent in enumerate(agents):
            if ag_idx in matched_dispatches:
                continue
            dispatch_time = parse_ts(agent["timestamp"])
            if not dispatch_time:
                continue
            delta = abs((sa["start_time"] - dispatch_time).total_seconds())
            if delta <= MAX_MATCH_WINDOW_S and (best_delta is None or delta < best_delta):
                best_match = ag_idx
                best_delta = delta

        if best_match is not None:
            agents[best_match]["tokens"] = sa["tokens"]
            agents[best_match]["estimated_cost_usd"] = sa["cost"]
            if sa["model"]:
                agents[best_match]["model"] = sa["model"]
            agents[best_match]["subagent_file"] = sa["file"]
            agents[best_match]["match_delta_s"] = round(best_delta, 1)
            if sa["meta"]:
                agents[best_match]["meta"] = sa["meta"]
            matched_dispatches.add(best_match)
            matched_subagents.add(sa_idx)

    # Report unmatched subagents
    for sa_idx, sa in enumerate(subagent_info):
        if sa_idx not in matched_subagents:
            agents.append({
                "description": f"[unmatched subagent: {sa['file']}]",
                "type": "unknown",
                "model": sa["model"] or "unknown",
                "prompt_preview": "",
                "background": False,
                "timestamp": str(sa["start_time"]) if sa["start_time"] else None,
                "tool_use_id": "",
                "tokens": sa["tokens"],
                "estimated_cost_usd": sa["cost"],
                "subagent_file": sa["file"],
                "match_confidence": "unmatched",
                "meta": sa["meta"],
            })


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python extract.py <session.jsonl> [--subagents-dir <path>] [--summary] [--metadata-only]")
        sys.exit(1)

    jsonl_path = sys.argv[1]
    subagents_dir = None
    summary_mode = "--summary" in sys.argv
    metadata_only = "--metadata-only" in sys.argv

    if metadata_only:
        result = extract_metadata_lite(jsonl_path)
        print(json.dumps(result, indent=2, default=str))
        sys.exit(0)

    if "--subagents-dir" in sys.argv:
        idx = sys.argv.index("--subagents-dir")
        if idx + 1 < len(sys.argv):
            subagents_dir = sys.argv[idx + 1]
    else:
        # Auto-detect: look for sibling directory with same name as the JSONL
        stem = Path(jsonl_path).stem
        candidate = Path(jsonl_path).parent / stem / "subagents"
        if candidate.is_dir():
            subagents_dir = str(candidate)

    result = extract_all_streaming(jsonl_path, subagents_dir, summary_mode)
    print(json.dumps(result, indent=2, default=str))
