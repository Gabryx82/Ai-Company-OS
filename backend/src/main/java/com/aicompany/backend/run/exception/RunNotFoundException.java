package com.aicompany.backend.run.exception;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(Long runId) {
        super("No run with id " + runId);
    }
}
