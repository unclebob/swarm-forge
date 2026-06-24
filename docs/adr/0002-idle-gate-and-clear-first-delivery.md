---
status: accepted
---

# Idle gate and clear-first delivery

The fork uses upstream's handoff harness as-is (queue, scripts, `logbook.jsonl`); the only engine discrepancy is **delivery**. Upstream does setup work at startup and never clears context between tasks — it types each handoff straight into the terminal and lets the terminal buffer it whether the agent is working or not. The fork instead requires every role to (1) do nothing until it receives a handoff and (2) start each task from a cleared session.

**Idle gate** — a prompt rule ("Wait for a handoff. Do not act without one.") plus removal of the startup-install directives from role prompts (install work moves to a separate setup skill). Additive prompt edits.

**Clear-first delivery** — `/clear` clears the session for **any** agent, so it cannot be sent to a working agent. Delivery therefore must know whether the receiver is idle or busy. Upstream tracks no such state, so the fork adds a minimal per-role **idle/busy marker**. Delivery then has two cases, both running `/clear` → re-inject the role bundle → send the task message:

- receiver **busy** — the handoff waits in upstream's queue (`.swarmforge/handoffs/queue/`); the receiver's **Stop hook** delivers it when the agent next stops.
- receiver **idle** — deliver immediately, because no stop will occur to trigger the hook.

The marker is set *busy* when a delivery starts and *idle* when the Stop hook finds the queue empty; the hook re-checks the queue before declaring idle to close the narrow "went idle just as a sender judged it busy" race.

**Re-injection is universal.** `/clear` wipes the session regardless of backend, so the role bundle is always re-sent after `/clear`.

**Delivery engine.** The central `swarm-handoff` script handles queueing, busy checking, and backend-aware delivery. The engine writes every outgoing message to a pending queue, checks the busy marker, and either delivers immediately (idle) or exits and lets the Stop hook drain the queue later (busy). The delivery path is backend-aware: for `claude` roles, `/clear` + re-inject is sent before the handoff body; for other backends, the handoff is delivered directly until their own clear-first mechanism is built.

Ready is implicit (idle + empty queue = ready). Upstream's startup "I'm awake" ping is kept only as an operator-visible **presence** signal — stamped a distinct `presence` type and excluded from the clear-first path, so the Stop hook never clears for it.
