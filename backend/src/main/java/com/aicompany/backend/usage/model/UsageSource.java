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
    /** Nothing to measure: the window is shown with its configured reset only. */
    MANUAL
}
