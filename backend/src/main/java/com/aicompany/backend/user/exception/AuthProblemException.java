package com.aicompany.backend.user.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

/**
 * The refusals of sign-in and of people management (ADR-024). One class, one
 * factory per problem: the problem is still named explicitly at every throw.
 */
public class AuthProblemException extends ProblemException {

    private AuthProblemException(ApiProblem problem, String detail) {
        super(problem, detail);
    }

    /**
     * The same answer for an unknown user, a wrong password, a disabled account
     * and a locked one: telling them apart would tell an attacker which
     * usernames exist.
     */
    public static AuthProblemException invalidCredentials() {
        return new AuthProblemException(ApiProblem.INVALID_CREDENTIALS, null);
    }

    public static AuthProblemException tooManyAttempts() {
        return new AuthProblemException(ApiProblem.TOO_MANY_LOGIN_ATTEMPTS, null);
    }

    public static AuthProblemException weakPassword(String why) {
        return new AuthProblemException(ApiProblem.WEAK_PASSWORD, why);
    }

    public static AuthProblemException currentPasswordWrong() {
        return new AuthProblemException(ApiProblem.CURRENT_PASSWORD_WRONG, null);
    }

    public static AuthProblemException usernameTaken(String username) {
        return new AuthProblemException(ApiProblem.USERNAME_TAKEN, "The username '" + username + "' is already in use");
    }

    public static AuthProblemException userNotFound(Long id) {
        return new AuthProblemException(ApiProblem.USER_NOT_FOUND, "No user with id " + id);
    }

    public static AuthProblemException lastAdmin() {
        return new AuthProblemException(ApiProblem.LAST_ADMIN, null);
    }

    public static AuthProblemException notAUser() {
        return new AuthProblemException(ApiProblem.NOT_A_USER_SESSION, null);
    }
}
