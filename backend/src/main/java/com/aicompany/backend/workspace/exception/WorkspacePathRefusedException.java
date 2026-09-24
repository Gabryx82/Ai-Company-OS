package com.aicompany.backend.workspace.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class WorkspacePathRefusedException extends ProblemException {

    public WorkspacePathRefusedException(String detail) {
        super(ApiProblem.WORKSPACE_PATH_REFUSED, detail);
    }
}
