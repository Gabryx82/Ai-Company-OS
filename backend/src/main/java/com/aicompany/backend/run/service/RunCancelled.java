package com.aicompany.backend.run.service;

/**
 * PHASE 32 (TD-39): published inside the transaction that cancels a run, delivered
 * after it commits -- the thread waiting on the engine is interrupted only once
 * the cancellation is the recorded state, so what it does next can only be a
 * no-op on a finished run.
 */
public record RunCancelled(Long runId) {
}
