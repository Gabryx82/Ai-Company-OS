package com.aicompany.backend.plan.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class PlanLockedException extends ProblemException {

    public PlanLockedException(String detail) {
        super(ApiProblem.PLAN_LOCKED, detail);
    }
}
