package com.aicompany.backend.plan.model;

import com.aicompany.backend.project.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * One phase of a project's implementation plan (ADR-021): its row in the index,
 * while its content lives in {@code docs/phases/PHASE_N.md} (ADR-020).
 *
 * <p>The approval is the Human-in-the-Loop gate of ADR-022: a task of this phase
 * starts only once the operator approved it. Columns mirror {@code V15}.
 */
@Entity
@Table(name = "project_phases")
public class ProjectPhase {

    public enum Status { PLANNED, IN_PROGRESS, DONE }

    public enum Approval { PENDING, APPROVED, CHANGES_REQUESTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 4000)
    private String objective;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Approval approval = Approval.PENDING;

    @Column(name = "approval_note", length = 2000)
    private String approvalNote;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "document_path", nullable = false, length = 500)
    private String documentPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ProjectPhase() {
    }

    public ProjectPhase(Project project, int number, String title, String objective, String documentPath) {
        this.project = project;
        this.number = number;
        this.title = title;
        this.objective = objective;
        this.documentPath = documentPath;
    }

    /** The operator approves: from now on the phase's tasks may start. */
    public void approve(String note) {
        this.approval = Approval.APPROVED;
        this.approvalNote = note;
        this.approvedAt = Instant.now();
    }

    /** The operator asks for changes: the phase's tasks may not start until approved again. */
    public void requestChanges(String note) {
        this.approval = Approval.CHANGES_REQUESTED;
        this.approvalNote = note;
        this.approvedAt = null;
    }

    public void moveTo(Status status) {
        this.status = status;
    }

    public boolean isApproved() {
        return approval == Approval.APPROVED;
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
    public Project getProject() { return project; }
    public Long getProjectId() { return project == null ? null : project.getId(); }
    public int getNumber() { return number; }
    public String getTitle() { return title; }
    public String getObjective() { return objective; }
    public Status getStatus() { return status; }
    public Approval getApproval() { return approval; }
    public String getApprovalNote() { return approvalNote; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getDocumentPath() { return documentPath; }
    public long getVersion() { return version; }
}
