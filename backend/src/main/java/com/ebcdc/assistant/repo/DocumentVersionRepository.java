package com.ebcdc.assistant.repo;

import com.ebcdc.assistant.domain.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {
    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNumDesc(UUID documentId);
    List<DocumentVersion> findByDocumentIdOrderByVersionNumDesc(UUID documentId);
    void deleteByDocumentId(UUID documentId);
}
