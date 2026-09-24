package com.aicompany.backend.plan.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** A plan.json that does not follow the format, with every offending field named. */
public class PlanInvalidException extends ProblemException {

    public PlanInvalidException(String detail, Map<String, String> errors) {
        super(ApiProblem.PLAN_INVALID, detail, errors);
    }
}
