package com.aicompany.backend.openclaw;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** What the console asked of OpenClaw and is not there: an agent OpenClaw does not have, a message never sent. */
public class OpenClawProblemException extends ProblemException {

    OpenClawProblemException(String detail) {
        super(ApiProblem.OPENCLAW_ITEM_NOT_FOUND, detail, Map.of());
    }
}
