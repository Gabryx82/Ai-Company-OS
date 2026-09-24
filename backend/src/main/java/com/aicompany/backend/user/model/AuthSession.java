package com.aicompany.backend.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A signed-in session (ADR-024 §2). The token itself is never stored: only its
 * SHA-256 digest, so the table cannot be used to impersonate anybody.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "token_digest", nullable = false, length = 64, updatable = false)
    private String tokenDigest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(length = 200)
    private String client;

    protected AuthSession() {
    }

    public AuthSession(AppUser user, String tokenDigest, Instant now, Instant expiresAt, String client) {
        this.user = user;
        this.tokenDigest = tokenDigest;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
        this.client = client;
    }

    public boolean isLive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void touch(Instant now) {
        lastSeenAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getClient() { return client; }
}
