package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

/** PHASE 32 (TD-49): no handoff with that id. */
public class HandoffNotFoundException extends ProblemException {
    public HandoffNotFoundException(Long id) {
        super(ApiProblem.RESOURCE_NOT_FOUND, "No handoff " + id);
    }
}
