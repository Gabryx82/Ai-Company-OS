package com.aicompany.backend.plan.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class PhaseNotFoundException extends ProblemException {

    public PhaseNotFoundException(String detail) {
        super(ApiProblem.PHASE_NOT_FOUND, detail);
    }
}
