# Agent Protocol

## 1. General rule
An agent receives one bounded task at a time and may work autonomously inside that scope.

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

## 4. Human-in-the-Loop
For early non-repetitive implementation tasks:
1. Explain the architecture and concepts.
2. Show the intended change.
3. Let the human validate/learn.
4. Implement only within approved scope.
5. Test.
6. Present the result/diff.
7. Human validates visually/functionally where relevant.

Automation can increase later for repetitive, well-tested tasks.

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
