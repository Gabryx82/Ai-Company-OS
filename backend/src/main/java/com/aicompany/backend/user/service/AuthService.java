package com.aicompany.backend.user.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.security.Caller;
import com.aicompany.backend.user.exception.AuthProblemException;
import com.aicompany.backend.user.model.AppUser;
import com.aicompany.backend.user.model.Role;
import com.aicompany.backend.user.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Sign-in, password changes and people management (ADR-024).
 *
 * <p>Sign-in refusals are recorded, not rolled back: the counter of wrong
 * passwords must survive the exception that reports them, which is why
 * {@link #login} does not roll back on {@link AuthProblemException}.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final SessionService sessions;
    private final PasswordEncoder encoder;
    private final SecurityLog log;
    private final LoginThrottle throttle;
    private final int lockThreshold;
    private final Duration lockDuration;
    /** Compared against when the username is unknown, so both paths cost one BCrypt check. */
    private final String decoyHash;

    public AuthService(AppUserRepository users, SessionService sessions, PasswordEncoder encoder, SecurityLog log,
                       LoginThrottle throttle,
                       @Value("${aicos.security.login.lock-after:5}") int lockThreshold,
                       @Value("${aicos.security.login.lock-for:PT15M}") Duration lockDuration) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.log = log;
        this.throttle = throttle;
        this.lockThreshold = lockThreshold;
        this.lockDuration = lockDuration;
        this.decoyHash = encoder.encode("decoy-password-never-used-" + System.nanoTime());
    }

    public record SignedIn(String token, Instant expiresAt, AppUser user) {
    }

    public static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    @Transactional(noRollbackFor = AuthProblemException.class)
    public SignedIn login(String rawUsername, String password, String source, String client) {
        String username = normalize(rawUsername);
        if (!throttle.allow(source)) {
            log.record(SecurityLog.LOGIN_THROTTLED, username, source, null);
            throw AuthProblemException.tooManyAttempts();
        }
        AppUser user = users.findByUsernameForUpdate(username).orElse(null);
        if (user == null) {
            encoder.matches(password == null ? "" : password, decoyHash);
            log.record(SecurityLog.LOGIN_FAILED, username, source, "unknown user");
            throw AuthProblemException.invalidCredentials();
        }
        Instant now = Instant.now();
        if (user.isLocked(now)) {
            log.record(SecurityLog.LOGIN_FAILED, username, source, "account locked until " + user.getLockedUntil());
            throw AuthProblemException.invalidCredentials();
        }
        boolean matches = encoder.matches(password == null ? "" : password, user.getPasswordHash());
        if (!matches) {
            boolean locked = user.recordFailure(lockThreshold, lockDuration, now);
            log.record(locked ? SecurityLog.ACCOUNT_LOCKED : SecurityLog.LOGIN_FAILED, username, source,
                    locked ? "locked for " + lockDuration : "wrong password");
            throw AuthProblemException.invalidCredentials();
        }
        if (!user.isEnabled()) {
            log.record(SecurityLog.LOGIN_FAILED, username, source, "account disabled");
            throw AuthProblemException.invalidCredentials();
        }
        user.recordLogin(now);
        SessionService.Issued issued = sessions.issue(user, client);
        log.record(SecurityLog.LOGIN_SUCCEEDED, username, source, "session " + issued.session().getId());
        return new SignedIn(issued.token(), issued.expiresAt(), user);
    }

    public void logout(Caller caller, String source) {
        if (caller.sessionId() != null) {
            sessions.revoke(caller.sessionId());
            log.record(SecurityLog.LOGOUT, caller.name(), source, "session " + caller.sessionId());
        }
    }

    @Transactional(readOnly = true)
    public AppUser current(Caller caller) {
        if (caller.userId() == null) {
            throw AuthProblemException.notAUser();
        }
        return users.findById(caller.userId()).orElseThrow(AuthProblemException::notAUser);
    }

    /** Changes one's own password; every other session of the person ends. */
    @Transactional
    public void changePassword(Caller caller, String current, String next, String source) {
        if (caller.userId() == null) {
            throw AuthProblemException.notAUser();
        }
        AppUser user = users.findByIdForUpdate(caller.userId()).orElseThrow(AuthProblemException::notAUser);
        if (!encoder.matches(current == null ? "" : current, user.getPasswordHash())) {
            log.record(SecurityLog.LOGIN_FAILED, user.getUsername(), source, "wrong current password on change");
            throw AuthProblemException.currentPasswordWrong();
        }
        PasswordPolicy.check(next, user.getUsername());
        if (encoder.matches(next, user.getPasswordHash())) {
            throw AuthProblemException.weakPassword("The new password must differ from the current one");
        }
        user.changePassword(encoder.encode(next), false);
        int ended = sessions.revokeAllOf(user.getId(), caller.sessionId());
        log.record(SecurityLog.PASSWORD_CHANGED, user.getUsername(), source, ended + " other sessions ended");
    }

    /** One's own display name: what the console shows in the top bar and in the greeting. Blank clears it. */
    @Transactional
    public AppUser updateOwnProfile(Caller caller, String displayName, String source) {
        if (caller.userId() == null) {
            throw AuthProblemException.notAUser();
        }
        AppUser user = users.findByIdForUpdate(caller.userId()).orElseThrow(AuthProblemException::notAUser);
        String name = displayName == null || displayName.isBlank() ? null : displayName.strip();
        user.configure(name, user.getRole(), user.isEnabled());
        log.record(SecurityLog.USER_UPDATED, user.getUsername(), source, "own display name");
        return users.saveAndFlush(user);
    }

    // --- people management (admin) ---------------------------------------------------

    @Transactional(readOnly = true)
    public List<AppUser> all() {
        return users.findAllByOrderByUsernameAsc();
    }

    @Transactional
    public AppUser create(Caller admin, String rawUsername, String displayName, Role role, String initialPassword) {
        String username = normalize(rawUsername);
        if (users.findByUsername(username).isPresent()) {
            throw AuthProblemException.usernameTaken(username);
        }
        PasswordPolicy.check(initialPassword, username);
        AppUser user = users.save(new AppUser(username, blankToNull(displayName), encoder.encode(initialPassword),
                role, true));
        log.record(SecurityLog.USER_CREATED, admin.name(), null, username + " as " + role);
        return user;
    }

    @Transactional
    public AppUser update(Caller admin, Long id, String displayName, Role role, boolean enabled,
                          Precondition precondition) {
        AppUser user = users.findByIdForUpdate(id).orElseThrow(() -> AuthProblemException.userNotFound(id));
        precondition.requireSatisfiedBy(user.getVersion());
        boolean losesAdmin = user.getRole() == Role.ADMIN && user.isEnabled() && (role != Role.ADMIN || !enabled);
        if (losesAdmin && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw AuthProblemException.lastAdmin();
        }
        user.configure(blankToNull(displayName), role, enabled);
        users.flush();
        if (!enabled) {
            sessions.revokeAllOf(user.getId(), null);
        }
        log.record(SecurityLog.USER_UPDATED, admin.name(), null,
                user.getUsername() + " role=" + role + " enabled=" + enabled);
        return user;
    }

    /** An admin sets a temporary password; the person must change it, and their sessions end. */
    @Transactional
    public AppUser resetPassword(Caller admin, Long id, String temporary, Precondition precondition) {
        AppUser user = users.findByIdForUpdate(id).orElseThrow(() -> AuthProblemException.userNotFound(id));
        precondition.requireSatisfiedBy(user.getVersion());
        PasswordPolicy.check(temporary, user.getUsername());
        user.changePassword(encoder.encode(temporary), true);
        users.flush();
        sessions.revokeAllOf(user.getId(), null);
        log.record(SecurityLog.PASSWORD_RESET, admin.name(), null, "for " + user.getUsername());
        return user;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
