package com.aicompany.backend.llm.model;

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

/**
 * Who serves models (ADR-018 §2): Ollama, the Anthropic API, OpenRouter, the
 * test echo, DwarfStar. Not a model, not a subscription, not an application.
 * Columns mirror {@code V13}.
 */
@Entity
@Table(name = "model_providers")
public class ModelProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40, updatable = false)
    private String key;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProviderKind kind;

    @Column(name = "engine_provider", length = 40)
    private String engineProvider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Billing billing;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ProviderStatus status;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "docs_url", length = 500)
    private String docsUrl;

    @Column(length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ModelProvider() {
    }

    public ModelProvider(String key, String name, ProviderKind kind, String engineProvider, Billing billing,
                         ProviderStatus status, String baseUrl, String docsUrl, String notes) {
        this.key = key;
        this.name = name;
        this.kind = kind;
        this.engineProvider = engineProvider;
        this.billing = billing;
        this.status = status;
        this.baseUrl = baseUrl;
        this.docsUrl = docsUrl;
        this.notes = notes;
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
    public ProviderKind getKind() { return kind; }
    public String getEngineProvider() { return engineProvider; }
    public Billing getBilling() { return billing; }
    public ProviderStatus getStatus() { return status; }
    public String getBaseUrl() { return baseUrl; }
    public String getDocsUrl() { return docsUrl; }
    public String getNotes() { return notes; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
