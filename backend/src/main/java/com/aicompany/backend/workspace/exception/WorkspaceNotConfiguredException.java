package com.aicompany.backend.workspace.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class WorkspaceNotConfiguredException extends ProblemException {

    public WorkspaceNotConfiguredException(String detail) {
        super(ApiProblem.WORKSPACE_NOT_CONFIGURED, detail);
    }
}
