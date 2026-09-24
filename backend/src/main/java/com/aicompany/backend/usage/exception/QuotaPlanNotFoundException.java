package com.aicompany.backend.usage.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class QuotaPlanNotFoundException extends ProblemException {

    public QuotaPlanNotFoundException(String key) {
        super(ApiProblem.QUOTA_PLAN_NOT_FOUND, "No quota window with key '" + key + "'");
    }
}
