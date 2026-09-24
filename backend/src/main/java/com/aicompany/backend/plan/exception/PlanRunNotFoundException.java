package com.aicompany.backend.plan.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class PlanRunNotFoundException extends ProblemException {

    public PlanRunNotFoundException(Long id) {
        super(ApiProblem.RESOURCE_NOT_FOUND, "No plan run with id " + id);
    }
}
