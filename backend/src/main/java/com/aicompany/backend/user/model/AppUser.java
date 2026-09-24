package com.aicompany.backend.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

/**
 * A person who signs in (ADR-024). Columns mirror {@code V20}. The password is
 * held only as a BCrypt hash; nothing in this class ever sees it in clear.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, updatable = false)
    private String username;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
    }

    public AppUser(String username, String displayName, String passwordHash, Role role, boolean mustChangePassword) {
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
        this.passwordChangedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** One more wrong password; at {@code threshold} the account is locked for {@code lock}. */
    public boolean recordFailure(int threshold, Duration lock, Instant now) {
        failedAttempts++;
        if (failedAttempts >= threshold) {
            failedAttempts = 0;
            lockedUntil = now.plus(lock);
            return true;
        }
        return false;
    }

    public void recordLogin(Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void changePassword(String newHash, boolean mustChangeAgain) {
        this.passwordHash = newHash;
        this.mustChangePassword = mustChangeAgain;
        this.passwordChangedAt = Instant.now();
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public void configure(String displayName, Role role, boolean enabled) {
        this.displayName = displayName;
        this.role = role;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
