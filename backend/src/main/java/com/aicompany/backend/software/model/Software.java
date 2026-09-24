package com.aicompany.backend.software.model;

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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A catalog entry of the Software Hub (ADR-019): what a program is for, and how
 * to recognise and open it. Never whether it is installed -- that is detected
 * when it is read (ADR-018 §5).
 *
 * <p>Columns mirror {@code V12__create_software_catalog.sql}; Hibernate validates.
 */
@Entity
@Table(name = "software")
public class Software {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, updatable = false)
    private String key;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SoftwareCategory category;

    @Column(nullable = false, length = 200)
    private String role;

    @Column(length = 1000)
    private String purpose;

    @Column(nullable = false, length = 1000)
    private String capabilities = "";

    @Column(name = "project_types", nullable = false, length = 500)
    private String projectTypes = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "launch_kind", nullable = false, length = 16)
    private LaunchKind launchKind;

    @Column(name = "app_id", length = 300)
    private String appId;

    @Column(length = 500)
    private String executable;

    @Column(name = "executable_args", length = 1000)
    private String executableArgs;

    @Column(name = "open_folder", nullable = false)
    private boolean openFolder;

    @Column(name = "cli_command", length = 200)
    private String cliCommand;

    @Column(length = 500)
    private String url;

    @Column(name = "health_url", length = 500)
    private String healthUrl;

    @Column(nullable = false)
    private boolean embeddable;

    @Column(name = "embed_note", length = 500)
    private String embedNote;

    @Column(name = "execution_target", nullable = false)
    private boolean executionTarget;

    @Column(length = 1000)
    private String requirements;

    @Column(length = 1000)
    private String configuration;

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    @Column(name = "incompatible_reason", length = 500)
    private String incompatibleReason;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Software() {
    }

    public Software(String key, SoftwareDefinition definition) {
        this.key = key;
        apply(definition);
    }

    /**
     * Replaces every descriptive and launch field. The key never changes: it is
     * the identity other rows and the orchestrator refer to.
     */
    public void apply(SoftwareDefinition d) {
        this.name = d.name().strip();
        this.category = d.category();
        this.role = d.role().strip();
        this.purpose = blankToNull(d.purpose());
        this.capabilities = Tags.join(d.capabilities());
        this.projectTypes = Tags.join(d.projectTypes());
        this.launchKind = d.launchKind();
        this.appId = blankToNull(d.appId());
        this.executable = blankToNull(d.executable());
        this.executableArgs = d.executableArgs() == null || d.executableArgs().isEmpty()
                ? null : String.join("\n", d.executableArgs());
        this.openFolder = d.openFolder();
        this.cliCommand = blankToNull(d.cliCommand());
        this.url = blankToNull(d.url());
        this.healthUrl = blankToNull(d.healthUrl());
        this.embeddable = d.embeddable();
        this.embedNote = blankToNull(d.embedNote());
        this.executionTarget = d.executionTarget();
        this.requirements = blankToNull(d.requirements());
        this.configuration = blankToNull(d.configuration());
        this.iconUrl = blankToNull(d.iconUrl());
        this.incompatibleReason = blankToNull(d.incompatibleReason());
        this.enabled = d.enabled() == null || d.enabled();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
    public long getVersion() { return version; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public SoftwareCategory getCategory() { return category; }
    public String getRole() { return role; }
    public String getPurpose() { return purpose; }
    public List<String> getCapabilities() { return Tags.split(capabilities); }
    public List<String> getProjectTypes() { return Tags.split(projectTypes); }
    public LaunchKind getLaunchKind() { return launchKind; }
    public String getAppId() { return appId; }
    public String getExecutable() { return executable; }
    public List<String> getExecutableArgs() {
        return executableArgs == null ? List.of() : Arrays.stream(executableArgs.split("\n")).toList();
    }
    public boolean isOpenFolder() { return openFolder; }
    public String getCliCommand() { return cliCommand; }
    public String getUrl() { return url; }
    public String getHealthUrl() { return healthUrl; }
    public boolean isEmbeddable() { return embeddable; }
    public String getEmbedNote() { return embedNote; }
    public boolean isExecutionTarget() { return executionTarget; }
    public String getRequirements() { return requirements; }
    public String getConfiguration() { return configuration; }
    public String getIconUrl() { return iconUrl; }
    public String getIncompatibleReason() { return incompatibleReason; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
