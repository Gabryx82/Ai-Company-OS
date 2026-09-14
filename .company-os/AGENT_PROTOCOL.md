# Agent Protocol

> **Since 2026-09-14 the project runs in Autonomous Project Mode with Human Final Review.**
> `.company-os/AUTONOMOUS_CHARTER.md` defines the authority and the hard stops;
> `.company-os/AUTONOMOUS_LOOP.md` defines the working loop. Where this document and the
> charter disagree, the charter wins — it is the newer decision. Everything here that the
> charter does not contradict still applies.

## 1. General rule
An agent receives one bounded task at a time and may work autonomously inside that scope.

Under Autonomous Project Mode the agent also **defines** the next task itself, using the
priority order in `AUTONOMOUS_LOOP.md` §4, and starts it without waiting.

## 2. Before working
Read, in this order:
1. `.company-os/PROJECT_STATE.md`
2. Current task `CONTEXT.yaml`
3. Only the required files listed there
4. Previous `HANDOFF.md`, if present

Do not recursively scan the entire repository unless the task explicitly requires an audit.

## 3. During implementation
The active agent may:
- inspect files relevant to the task;
- create or modify task-related files;
- add tests;
- run tests and linters;
- perform local refactors needed by the task;
- update task documentation.

The active agent must NOT autonomously:
- replace the global technology stack;
- delete major subsystems;
- change global architecture outside task scope;
- perform destructive Git operations;
- expose secrets;
- send external messages;
- make purchases or payments;
- perform irreversible external actions.

Those require explicit human approval.

## 4. Human review — where the gate is now
**Superseded for the per-task case by `AUTONOMOUS_CHARTER.md`.** The gate is no longer before
each task; it is `master`, and it is final.

Inside the loop the agent explains its reasoning in the task artifacts and the ADRs instead of
in a conversation, and reviews its own diff adversarially (`AUTONOMOUS_LOOP.md` §2) in place of
the human pass that used to sit there. Nothing reaches `master` without a human.

The hard stops in the charter are the only points where work pauses for a person.

## 5. End-of-task mandatory artifacts
Every completed task must contain:
- `TASK.md`
- `IMPLEMENTATION.md`
- `ARTIFACT.md`
- `HANDOFF.md`

`HANDOFF.md` must be concise and optimized for the next agent.

## 6. Alternation
Preferred pattern:
Claude Code -> artifact/handoff -> Codex
or
Codex -> artifact/handoff -> Claude Code

Do not make the second model redo a completed analysis. Use differential review.

## 7. Chat output
Keep terminal/chat narration concise.
Persist detailed explanations in project Markdown artifacts.
