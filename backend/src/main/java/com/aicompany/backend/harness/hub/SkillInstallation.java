package com.aicompany.backend.harness.hub;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Where a library entry came from when the Hub installed or imported it, and
 * what the last update check found upstream (V30, ADR-036 §5).
 */
@Entity
@Table(name = "skill_installations")
public class SkillInstallation {

    /** A skill imported from an application on this machine; its reference is {@code location:relative-path}. */
    public static final String APP = "app";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private Long resourceId;

    @Column(nullable = false, length = 32, updatable = false)
    private String provider;

    @Column(nullable = false, length = 500, updatable = false)
    private String reference;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "installed_version", length = 120)
    private String installedVersion;

    @Column(name = "installed_fingerprint", nullable = false, length = 64)
    private String installedFingerprint;

    @Column(name = "local_fingerprint", nullable = false, length = 64)
    private String localFingerprint;

    @Column(name = "installed_at", nullable = false)
    private Instant installedAt;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "latest_version", length = 120)
    private String latestVersion;

    @Column(name = "latest_fingerprint", length = 64)
    private String latestFingerprint;

    @Column(name = "check_error", length = 500)
    private String checkError;

    @Version
    @Column(nullable = false)
    private long version;

    protected SkillInstallation() {
    }

    public SkillInstallation(Long resourceId, String provider, String reference, String sourceUrl, String installedVersion,
                             String installedFingerprint, String localFingerprint) {
        this.resourceId = resourceId;
        this.provider = provider;
        this.reference = reference;
        this.sourceUrl = sourceUrl;
        this.installedVersion = installedVersion;
        this.installedFingerprint = installedFingerprint;
        this.localFingerprint = localFingerprint;
        this.installedAt = Instant.now();
    }

    /** The package was installed again at its latest state. */
    public void reinstalled(String version, String fingerprint, String local) {
        this.installedVersion = version;
        this.installedFingerprint = fingerprint;
        this.localFingerprint = local;
        this.installedAt = Instant.now();
        this.latestVersion = version;
        this.latestFingerprint = fingerprint;
        this.checkError = null;
    }

    /** The operator brought a change of an application's copy into the library: that is the library's state now. */
    public void adoptedLocally(String local) {
        this.localFingerprint = local;
    }

    public void checked(String latestVersion, String latestFingerprint) {
        this.checkedAt = Instant.now();
        this.latestVersion = latestVersion;
        this.latestFingerprint = latestFingerprint;
        this.checkError = null;
    }

    public void checkFailed(String error) {
        this.checkedAt = Instant.now();
        this.checkError = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
    }

    public boolean updateAvailable() {
        return latestFingerprint != null && !latestFingerprint.equals(installedFingerprint);
    }

    public Long getId() { return id; }
    public Long getResourceId() { return resourceId; }
    public String getProvider() { return provider; }
    public String getReference() { return reference; }
    public String getSourceUrl() { return sourceUrl; }
    public String getInstalledVersion() { return installedVersion; }
    public String getInstalledFingerprint() { return installedFingerprint; }
    public String getLocalFingerprint() { return localFingerprint; }
    public Instant getInstalledAt() { return installedAt; }
    public Instant getCheckedAt() { return checkedAt; }
    public String getLatestVersion() { return latestVersion; }
    public String getLatestFingerprint() { return latestFingerprint; }
    public String getCheckError() { return checkError; }
}
