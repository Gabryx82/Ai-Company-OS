package com.aicompany.backend.blueprint.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class RelationExistsException extends ProblemException {

    public RelationExistsException(String detail) {
        super(ApiProblem.BLUEPRINT_RELATION_EXISTS, detail);
    }
}
