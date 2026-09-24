package com.aicompany.backend.usage.model;

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

import java.time.Instant;
import java.time.LocalTime;

/**
 * One quota window of one subject -- Claude Code's weekly limit, Codex's five
 * hours, the daily budget of billed engine runs. Columns mirror {@code V13}.
 */
@Entity
@Table(name = "quota_plans")
public class QuotaPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, updatable = false)
    private String key;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 64)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private UsageSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "window_kind", nullable = false, length = 16)
    private WindowKind windowKind;

    @Column(name = "reset_weekday")
    private Integer resetWeekday;

    @Column(name = "reset_time")
    private LocalTime resetTime;

    @Column(name = "reset_zone", length = 64)
    private String resetZone;

    @Column(name = "limit_note", length = 500)
    private String limitNote;

    @Column(name = "usage_url", length = 500)
    private String usageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected QuotaPlan() {
    }

    public QuotaPlan(String key, String name, String subject, UsageSource source, WindowKind windowKind,
                     Integer resetWeekday, LocalTime resetTime, String resetZone, String limitNote, String usageUrl) {
        this.key = key;
        this.name = name;
        this.subject = subject;
        this.source = source;
        this.windowKind = windowKind;
        this.resetWeekday = resetWeekday;
        this.resetTime = resetTime;
        this.resetZone = resetZone;
        this.limitNote = limitNote;
        this.usageUrl = usageUrl;
    }

    /** The operator states when the window resets, where no source measures it. */
    public void anchor(Integer resetWeekday, LocalTime resetTime, String resetZone, String limitNote) {
        this.resetWeekday = resetWeekday;
        this.resetTime = resetTime;
        this.resetZone = resetZone;
        this.limitNote = limitNote;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getSubject() { return subject; }
    public UsageSource getSource() { return source; }
    public WindowKind getWindowKind() { return windowKind; }
    public Integer getResetWeekday() { return resetWeekday; }
    public LocalTime getResetTime() { return resetTime; }
    public String getResetZone() { return resetZone; }
    public String getLimitNote() { return limitNote; }
    public String getUsageUrl() { return usageUrl; }
    public long getVersion() { return version; }
}
