package com.aicompany.backend.plan.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class PhaseNotApprovedException extends ProblemException {

    public PhaseNotApprovedException(String detail) {
        super(ApiProblem.PHASE_NOT_APPROVED, detail);
    }
}
