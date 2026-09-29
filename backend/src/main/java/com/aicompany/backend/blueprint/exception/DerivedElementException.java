package com.aicompany.backend.blueprint.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class DerivedElementException extends ProblemException {

    public DerivedElementException(String detail) {
        super(ApiProblem.BLUEPRINT_DERIVED_ELEMENT, detail);
    }
}
