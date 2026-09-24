package com.aicompany.backend.plan.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class PlanningInProgressException extends ProblemException {

    public PlanningInProgressException(String detail) {
        super(ApiProblem.PLANNING_IN_PROGRESS, detail);
    }
}
