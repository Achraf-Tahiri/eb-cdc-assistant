package com.ebcdc.assistant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clarification_requests")
public class ClarificationRequest {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "document_id", nullable = false)
    private UUID documentId;
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;
    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;
    @Column(nullable = false, columnDefinition = "text")
    private String question;
    @Column(name = "section_ref")
    private String sectionRef;
    @Column(name = "requested_at")
    private Instant requestedAt = Instant.now();
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(columnDefinition = "text")
    private String resolution;
    @Column(name = "days_since_provider_received")
    private Double daysSinceProviderReceived;

    public ClarificationRequest() {}
    public ClarificationRequest(UUID documentId, UUID providerId, UUID requestedBy,
                                String question, String sectionRef, Double daysSince) {
        this.documentId = documentId; this.providerId = providerId;
        this.requestedBy = requestedBy; this.question = question;
        this.sectionRef = sectionRef; this.daysSinceProviderReceived = daysSince;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getProviderId() { return providerId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getQuestion() { return question; }
    public String getSectionRef() { return sectionRef; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolution() { return resolution; }
    public Double getDaysSinceProviderReceived() { return daysSinceProviderReceived; }
    public void resolve(String resolution) {
        this.resolution = resolution; this.resolvedAt = Instant.now();
    }
}
