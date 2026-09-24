package com.aicompany.backend.user.model;

/**
 * What a signed-in person may do (ADR-024 §3). Two roles and no more: the
 * operator works; the admin also destroys, manages people and reads the
 * security log.
 */
public enum Role {
    ADMIN,
    OPERATOR;

    public String authority() {
        return "ROLE_" + name();
    }
}
