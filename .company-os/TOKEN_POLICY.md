# Token & Context Policy

## Objective
Minimize repeated context while preserving engineering quality.

## Rules
- Prefer targeted file reads.
- Read `CONTEXT.yaml` before exploring.
- Do not re-read information already summarized in `HANDOFF.md` unless verification is necessary.
- Do not recursively inspect `node_modules`, build outputs, virtual environments, caches, binaries, generated assets, or archives.
- Avoid rewriting unchanged documentation.
- Keep chat responses concise.
- Store important reasoning outcomes as ADRs or implementation artifacts.
- Use local models for cheap classification, summarization, boilerplate and simple tests when the router exists.

## Suggested operating levels

### Low-risk task
- One primary agent.
- Local review if useful.
- No cloud cross-review by default.

### Medium-risk task
- One cloud primary agent.
- Targeted review.
- No full duplicate repository scan.

### High-risk / architectural task
- One cloud primary agent.
- Independent differential review by the other cloud agent.
- Human approval before irreversible architecture changes.

## Context budget philosophy
Start small and expand only when necessary:
- Initial files: <= 8 whenever practical.
- Prefer summaries over entire historical documents.
- Full repository scan: only for explicit audit/repository-mapping tasks.
