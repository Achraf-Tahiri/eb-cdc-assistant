package com.ebcdc.assistant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_versions")
public class DocumentVersion {
    @Id
    private UUID id = UUID.randomUUID();
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;
    @Column(name = "version_num", nullable = false)
    private int versionNum;
    @Column(nullable = false, columnDefinition = "text")
    private String content;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "author_id", nullable = false)
    private AppUser author;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public DocumentVersion() {}
    public DocumentVersion(DocumentEntity document, int versionNum, String content, AppUser author) {
        this.document = document; this.versionNum = versionNum;
        this.content = content; this.author = author;
    }

    public UUID getId() { return id; }
    public int getVersionNum() { return versionNum; }
    public String getContent() { return content; }
    public AppUser getAuthor() { return author; }
    public Instant getCreatedAt() { return createdAt; }
}
