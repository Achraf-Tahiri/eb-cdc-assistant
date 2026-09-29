package com.ebcdc.assistant.repo;

import com.ebcdc.assistant.domain.JobStatus;
import com.ebcdc.assistant.domain.JobType;
import com.ebcdc.assistant.domain.RagJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagJobRepository extends JpaRepository<RagJob, UUID> {
    Optional<RagJob> findFirstByStatusOrderByCreatedAtAsc(JobStatus status);
    List<RagJob> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);
    Optional<RagJob> findFirstByDocumentIdAndJobTypeAndStatusOrderByFinishedAtDesc(
            UUID documentId, JobType jobType, JobStatus status);
    void deleteByDocumentId(UUID documentId);
}
