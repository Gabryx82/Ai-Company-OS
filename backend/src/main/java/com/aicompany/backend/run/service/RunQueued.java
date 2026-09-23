package com.aicompany.backend.run.service;

/**
 * Published inside the transaction that records a run, delivered after it commits
 * (ADR-016 §3). An executor that picked the run up before the commit would look
 * for a row that is not yet visible -- or, worse, run it and then watch the
 * transaction that created it roll back.
 */
public record RunQueued(Long runId) {
}
