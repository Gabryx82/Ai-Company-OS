package com.aicompany.backend.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One line of the security log (ADR-024 §5). Append-only: nothing updates or deletes it. */
@Entity
@Table(name = "security_events")
public class SecurityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 40, updatable = false)
    private String type;

    @Column(length = 64, updatable = false)
    private String principal;

    @Column(length = 64, updatable = false)
    private String source;

    @Column(length = 500, updatable = false)
    private String detail;

    protected SecurityEvent() {
    }

    public SecurityEvent(String type, String principal, String source, String detail) {
        this.occurredAt = Instant.now();
        this.type = type;
        this.principal = principal;
        this.source = source;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getType() { return type; }
    public String getPrincipal() { return principal; }
    public String getSource() { return source; }
    public String getDetail() { return detail; }
}
