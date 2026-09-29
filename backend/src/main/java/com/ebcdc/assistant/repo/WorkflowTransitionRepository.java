package com.ebcdc.assistant.repo;

import com.ebcdc.assistant.domain.WorkflowAction;
import com.ebcdc.assistant.domain.WorkflowState;
import com.ebcdc.assistant.domain.WorkflowTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, Long> {
    List<WorkflowTransition> findByDocumentIdOrderByOccurredAtAscIdAsc(UUID documentId);
    Optional<WorkflowTransition> findFirstByDocumentIdAndToStateOrderByOccurredAtDesc(
            UUID documentId, WorkflowState toState);
    long countByDocumentIdAndAction(UUID documentId, WorkflowAction action);
    void deleteByDocumentId(UUID documentId);
}
