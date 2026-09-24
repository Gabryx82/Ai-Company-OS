package com.aicompany.backend.llm.model;

import com.aicompany.backend.catalog.Tags;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A catalogued LLM (ADR-018 §2). {@code key} is the engine identifier
 * {@code provider:model}, the same string {@code agents.model} holds. Columns
 * mirror {@code V13}.
 */
@Entity
@Table(name = "llm_models")
public class LlmModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200, updatable = false)
    private String key;

    @Column(name = "provider_key", nullable = false, length = 40)
    private String providerKey;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ModelRole role;

    @Column(nullable = false, length = 500)
    private String capabilities = "";

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "size_gb", precision = 7, scale = 1)
    private BigDecimal sizeGb;

    @Column(length = 20)
    private String parameters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ModelLifecycle lifecycle;

    @Column(name = "replaced_by", length = 200)
    private String replacedBy;

    @Column(length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected LlmModel() {
    }

    public LlmModel(String key, String providerKey, String displayName, ModelRole role, List<String> capabilities,
                    Integer contextWindow, BigDecimal sizeGb, String parameters, ModelLifecycle lifecycle,
                    String replacedBy, String notes) {
        this.key = key;
        this.providerKey = providerKey;
        this.displayName = displayName;
        this.role = role;
        this.capabilities = Tags.join(capabilities);
        this.contextWindow = contextWindow;
        this.sizeGb = sizeGb;
        this.parameters = parameters;
        this.lifecycle = lifecycle;
        this.replacedBy = replacedBy;
        this.notes = notes;
    }

    /** The operator re-classifies a model: role, lifecycle, replacement, notes. */
    public void classify(ModelRole role, ModelLifecycle lifecycle, String replacedBy, String notes) {
        this.role = role;
        this.lifecycle = lifecycle;
        this.replacedBy = replacedBy == null || replacedBy.isBlank() ? null : replacedBy.strip();
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
    public String getProviderKey() { return providerKey; }
    public String getDisplayName() { return displayName; }
    public ModelRole getRole() { return role; }
    public List<String> getCapabilities() { return Tags.split(capabilities); }
    public Integer getContextWindow() { return contextWindow; }
    public BigDecimal getSizeGb() { return sizeGb; }
    public String getParameters() { return parameters; }
    public ModelLifecycle getLifecycle() { return lifecycle; }
    public String getReplacedBy() { return replacedBy; }
    public String getNotes() { return notes; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
