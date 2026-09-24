package com.aicompany.backend.user.service;

import com.aicompany.backend.security.Caller;
import com.aicompany.backend.user.model.AppUser;
import com.aicompany.backend.user.model.AuthSession;
import com.aicompany.backend.user.repository.AuthSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Session tokens (ADR-024 §2): 256 random bits, handed to the client once and
 * stored only as a SHA-256 digest. A session ends when it is revoked (logout,
 * password change, account disabled), when it has been idle too long, or when
 * its absolute lifetime is over -- whichever comes first.
 */
@Service
public class SessionService {

    /** Recognisable in a log or a leaked file, and never confused with a configured service token. */
    public static final String TOKEN_PREFIX = "aicos_s_";

    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final AuthSessionRepository sessions;
    private final Duration idle;
    private final Duration lifetime;
    private final SecureRandom random = new SecureRandom();

    public SessionService(AuthSessionRepository sessions,
                          @Value("${aicos.security.session.idle:PT8H}") Duration idle,
                          @Value("${aicos.security.session.lifetime:PT72H}") Duration lifetime) {
        this.sessions = sessions;
        this.idle = idle;
        this.lifetime = lifetime;
    }

    public record Issued(String token, Instant expiresAt, AuthSession session) {
    }

    @Transactional
    public Issued issue(AppUser user, String client) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        AuthSession session = sessions.save(new AuthSession(user, digest(token), now, now.plus(lifetime),
                SecurityLog.clean(client, 200)));
        return new Issued(token, session.getExpiresAt(), session);
    }

    /** The caller a session token stands for, if the session is still alive. */
    @Transactional
    public Optional<Caller> resolve(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<AuthSession> found = sessions.findByDigest(digest(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AuthSession session = found.get();
        Instant now = Instant.now();
        AppUser user = session.getUser();
        if (!session.isLive(now) || !user.isEnabled() || session.getLastSeenAt().plus(idle).isBefore(now)) {
            return Optional.empty();
        }
        if (session.getLastSeenAt().plus(TOUCH_EVERY).isBefore(now)) {
            session.touch(now);
        }
        return Optional.of(Caller.user(user.getUsername(), user.getRole(), user.getId(), session.getId()));
    }

    @Transactional
    public void revoke(Long sessionId) {
        sessions.findById(sessionId).ifPresent(session -> session.revoke(Instant.now()));
    }

    @Transactional
    public int revokeAllOf(Long userId, Long keep) {
        return sessions.revokeAllOf(userId, keep, Instant.now());
    }

    public Duration idle() {
        return idle;
    }

    static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
