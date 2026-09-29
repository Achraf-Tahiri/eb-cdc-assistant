package com.ebcdc.assistant.repo;

import com.ebcdc.assistant.domain.ClarificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ClarificationRequestRepository extends JpaRepository<ClarificationRequest, UUID> {
    @org.springframework.data.jpa.repository.Query("select c.documentId from ClarificationRequest c where c.id = :id")
    java.util.Optional<UUID> findDocumentIdById(@org.springframework.data.repository.query.Param("id") UUID id);
    List<ClarificationRequest> findByDocumentIdOrderByRequestedAtAsc(UUID documentId);
    long countByDocumentIdAndResolvedAtIsNull(UUID documentId);
    long countByDocumentId(UUID documentId);
    void deleteByDocumentId(UUID documentId);
}
