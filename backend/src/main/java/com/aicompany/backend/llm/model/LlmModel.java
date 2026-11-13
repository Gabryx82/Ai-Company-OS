package com.aicompany.backend.llm.model;

import com.aicompany.backend.catalog.Tags;
import com.aicompany.backend.persistence.DatabaseClock;
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

    /** PHASE 32 (TD-66): the other roles this model serves besides its own, comma-separated; null for none. */
    @Column(name = "also_serves", length = 200)
    private String alsoServes;

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

    /** PHASE 35 (ADR-041 §11): the window asked of Ollama (num_ctx); null leaves the engine's default. */
    @Column(name = "num_ctx")
    private Integer numCtx;

    /** PHASE 35: how long Ollama keeps the model loaded after an answer ("5m", "0", "-1"). */
    @Column(name = "keep_alive", length = 8)
    private String keepAlive;

    /** PHASE 35: the quantization recommended for this machine; the model file decides, this informs. */
    @Column(length = 32)
    private String quantization;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "input_price_per_mtok", precision = 12, scale = 4)
    private java.math.BigDecimal inputPricePerMtok;

    @Column(name = "output_price_per_mtok", precision = 12, scale = 4)
    private java.math.BigDecimal outputPricePerMtok;

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
        alsoServe(getAlsoServes()); // its own role is never also one of the others
    }

    /**
     * PHASE 35 (ADR-041 §11): how the model runs. A null argument keeps what is there, so a
     * caller sets only what it was told; the database checks the forms.
     */
    public void tune(Integer numCtx, String keepAlive, String quantization) {
        if (numCtx != null) {
            this.numCtx = numCtx;
        }
        if (keepAlive != null) {
            this.keepAlive = keepAlive.strip();
        }
        if (quantization != null) {
            this.quantization = quantization.strip();
        }
    }

    public Integer getNumCtx() { return numCtx; }
    public String getKeepAlive() { return keepAlive; }
    public String getQuantization() { return quantization; }

    /** PHASE 32 (TD-66): the other roles this model serves; its own is left out. */
    public void alsoServe(java.util.Collection<ModelRole> roles) {
        List<String> names = roles == null ? List.of() : roles.stream().filter(r -> r != null && r != role)
                .distinct().sorted().map(Enum::name).toList();
        this.alsoServes = names.isEmpty() ? null : String.join(",", names);
    }

    public List<ModelRole> getAlsoServes() {
        return Tags.split(alsoServes).stream()
                .flatMap(name -> java.util.Arrays.stream(ModelRole.values()).filter(r -> r.name().equals(name)))
                .toList();
    }

    /** Whether work of this role may go to this model: its own role, or one it also serves. */
    public boolean serves(ModelRole wanted) {
        return role == wanted || getAlsoServes().contains(wanted);
    }

    @PrePersist
    void onCreate() {
        Instant now = DatabaseClock.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = DatabaseClock.now();
    }

    public Long getId() { return id; }
    /** PHASE 24 (ADR-031): USD per million tokens; null when not known. */
    public void price(java.math.BigDecimal input, java.math.BigDecimal output) {
        this.inputPricePerMtok = input;
        this.outputPricePerMtok = output;
    }

    public java.math.BigDecimal getInputPricePerMtok() { return inputPricePerMtok; }
    public java.math.BigDecimal getOutputPricePerMtok() { return outputPricePerMtok; }

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
