# Context Engineering Policy

## Context priority
1. Current task
2. Current project state
3. Explicitly required files
4. Direct dependencies of files being changed
5. Relevant ADRs
6. Previous handoff
7. Semantic memory / secondary knowledge
8. Broader repository context only when needed

## Context package
Each task should eventually compile a minimal context package containing:
- objective;
- constraints;
- relevant project state;
- relevant code;
- active rules;
- agent skills;
- tool permissions;
- previous decisions;
- expected output and tests.

## Exclusions
Unless explicitly required, exclude:
- unrelated projects;
- old generated builds;
- dependencies/vendor folders;
- duplicated documentation;
- stale chat transcripts;
- secrets and credential files.

## Traceability
Whenever context affects a decision, prefer recording the source file/ADR/task rather than relying on undocumented model memory.
