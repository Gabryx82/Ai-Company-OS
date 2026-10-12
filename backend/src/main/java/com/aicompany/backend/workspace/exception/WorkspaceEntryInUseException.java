package com.aicompany.backend.workspace.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

/**
 * PHASE 33 (ADR-040): a rename refused because a record points at the file (a task's
 * document would point at nothing), or because Windows refused it -- most often
 * another program holding the file open.
 */
public class WorkspaceEntryInUseException extends ProblemException {

    public WorkspaceEntryInUseException(String detail) {
        super(ApiProblem.WORKSPACE_ENTRY_IN_USE, detail);
    }
}
