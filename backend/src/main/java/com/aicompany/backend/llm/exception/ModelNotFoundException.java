package com.aicompany.backend.llm.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class ModelNotFoundException extends ProblemException {

    public ModelNotFoundException(String key) {
        super(ApiProblem.MODEL_NOT_FOUND, "No catalogued model with key '" + key + "'");
    }
}
