package com.aicompany.backend.usage.model;

/**
 * Where a usage figure comes from. {@code quota_plans_source_check} (V13).
 *
 * <p>No public API exposes the quota of a Claude or ChatGPT subscription; what
 * exists is local and real -- the session logs Codex and Claude Code write on
 * this machine -- and the runs this control plane recorded itself.
 */
public enum UsageSource {
    /** {@code ~/.codex/sessions}: Codex records its own rate limits, with reset times. */
    CODEX_LOCAL,
    /** {@code ~/.claude/projects}: Claude Code records tokens per message; limits are not recorded. */
    CLAUDE_CODE_LOCAL,
    /** {@code task_runs}: tokens of the runs this control plane sent to a billed provider. */
    ENGINE_RUNS,
    /** PHASE 33 (ADR-038): the model quotas an Antigravity app keeps in its state database. */
    ANTIGRAVITY_LOCAL,
    /** PHASE 33 (ADR-038): Kimi Code's own answer to {@code GET /usages}, with the operator's key. */
    KIMI_CODE_API,
    /** Nothing to measure: the window is shown with its configured reset only. */
    MANUAL
}
