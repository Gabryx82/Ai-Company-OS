package com.aicompany.backend.llm.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class ProviderNotFoundException extends ProblemException {

    public ProviderNotFoundException(String key) {
        super(ApiProblem.PROVIDER_NOT_FOUND, "No model provider with key '" + key + "'");
    }
}
