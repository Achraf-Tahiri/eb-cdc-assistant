package com.ebcdc.assistant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rag_jobs")
public class RagJob {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "document_id", nullable = false)
    private UUID documentId;
    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;
    @Column(name = "input_ref", columnDefinition = "text")
    private String inputRef;
    @Column(columnDefinition = "text")
    private String result;
    @Column(columnDefinition = "text")
    private String error;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;

    public RagJob() {}
    public RagJob(UUID documentId, JobType jobType, String inputRef) {
        this.documentId = documentId; this.jobType = jobType; this.inputRef = inputRef;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public JobType getJobType() { return jobType; }
    public JobStatus getStatus() { return status; }
    public String getInputRef() { return inputRef; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public void markRunning() { this.status = JobStatus.RUNNING; }
    public void markDone(String result) {
        this.status = JobStatus.DONE; this.result = result; this.finishedAt = Instant.now();
    }
    public void markFailed(String error) {
        this.status = JobStatus.FAILED; this.error = error; this.finishedAt = Instant.now();
    }
}
