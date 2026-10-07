package com.aicompany.backend.terminal;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

/** No running shell with that id belongs to the caller: it ended, or it is someone else's. */
public class TerminalNotFoundException extends ProblemException {

    public TerminalNotFoundException(String id) {
        super(ApiProblem.TERMINAL_NOT_FOUND, "No running terminal " + id);
    }
}
