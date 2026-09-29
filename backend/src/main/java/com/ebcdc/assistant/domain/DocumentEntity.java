package com.ebcdc.assistant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class DocumentEntity {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(nullable = false)
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    private DocType docType;
    @Column(name = "project_code")
    private String projectCode;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "provider_id")
    private Provider provider;
    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false)
    private WorkflowState currentState = WorkflowState.BROUILLON;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public DocumentEntity() {}
    public DocumentEntity(String title, DocType docType, String projectCode,
                          Provider provider, AppUser createdBy) {
        this.title = title; this.docType = docType; this.projectCode = projectCode;
        this.provider = provider; this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public DocType getDocType() { return docType; }
    public String getProjectCode() { return projectCode; }
    public Provider getProvider() { return provider; }
    public WorkflowState getCurrentState() { return currentState; }
    public void setCurrentState(WorkflowState s) { this.currentState = s; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
