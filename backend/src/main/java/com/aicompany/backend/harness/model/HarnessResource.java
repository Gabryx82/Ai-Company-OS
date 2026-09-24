package com.aicompany.backend.harness.model;

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
import java.util.List;

/**
 * One entry of the harness catalog (ADR-018 §3, ADR-023): a skill, a knowledge
 * source, an MCP server, a tool, a framework or a template provider. The kinds
 * share their attributes today, and the closed {@link Kind} keeps them apart.
 * Columns mirror {@code V18}.
 */
@Entity
@Table(name = "harness_resources")
public class HarnessResource {

    public enum Kind { SKILL, KNOWLEDGE, MCP, TOOL, FRAMEWORK, TEMPLATE_PROVIDER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, updatable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Kind kind;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 500)
    private String tags = "";

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "search_url", length = 500)
    private String searchUrl;

    @Column(length = 4000)
    private String configuration;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected HarnessResource() {
    }

    public HarnessResource(String key, Kind kind, String name, String description, List<String> tags,
                           String sourceUrl, String searchUrl, String configuration) {
        this.key = key;
        this.kind = kind;
        this.name = name;
        this.description = description;
        this.tags = Tags.join(tags);
        this.sourceUrl = sourceUrl;
        this.searchUrl = searchUrl;
        this.configuration = configuration;
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
    public Kind getKind() { return kind; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<String> getTags() { return Tags.split(tags); }
    public String getSourceUrl() { return sourceUrl; }
    public String getSearchUrl() { return searchUrl; }
    public String getConfiguration() { return configuration; }
    public long getVersion() { return version; }
}
