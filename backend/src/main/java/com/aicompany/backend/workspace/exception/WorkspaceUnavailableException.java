package com.aicompany.backend.workspace.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class WorkspaceUnavailableException extends ProblemException {

    public WorkspaceUnavailableException(String detail) {
        super(ApiProblem.WORKSPACE_UNAVAILABLE, detail);
    }
}
