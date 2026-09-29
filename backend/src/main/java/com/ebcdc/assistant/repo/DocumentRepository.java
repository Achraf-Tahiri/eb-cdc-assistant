package com.ebcdc.assistant.repo;

import com.ebcdc.assistant.domain.DocumentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DocumentEntity d where d.id = :id")
    Optional<DocumentEntity> findByIdForUpdate(@Param("id") UUID id);

    List<DocumentEntity> findAllByOrderByCreatedAtDesc();

    List<DocumentEntity> findByProviderIdOrderByCreatedAtDesc(UUID providerId);
}
