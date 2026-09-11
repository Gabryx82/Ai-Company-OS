# TASK-000 — Existing Repository Audit

## Owner
Claude Code / Opus 5

## Reviewer
Codex / GPT-6 Astra (differential review only)

## Objective
Audit the existing repository at:

`C:\Users\gabry\AI-Company-OS`

before any new architecture or implementation is imposed.

## Safety
READ/ANALYSIS FIRST.
Do not modify existing application source code during the audit.
Creating or updating audit/documentation artifacts is allowed.

## Required outputs
Create:

`docs/audit/CURRENT_ARCHITECTURE.md`
`docs/audit/CURRENT_FEATURES.md`
`docs/audit/CURRENT_STACK.md`
`docs/audit/REUSABLE_COMPONENTS.md`
`docs/audit/TECHNICAL_DEBT.md`
`docs/audit/GAP_ANALYSIS.md`
`docs/audit/MIGRATION_MAP.md`

Also create:
`tasks/TASK-000/IMPLEMENTATION.md`
`tasks/TASK-000/ARTIFACT.md`
`tasks/TASK-000/HANDOFF.md`

Update:
`.company-os/PROJECT_STATE.md`

## Audit questions
- What already exists?
- What works?
- What is incomplete?
- What can be reused?
- What should be refactored?
- What should be replaced?
- What should be removed?
- What target Company OS modules are already partially represented?
- What are the most dangerous architectural risks?
- What is the minimum viable migration path?
- What should TASK-001 be?

## Classification
Every significant existing component should receive one status:
- KEEP
- REFACTOR
- REPLACE
- DELETE
- NEW

## Final constraint
Do NOT start TASK-001.
Stop after producing the audit and handoff.
