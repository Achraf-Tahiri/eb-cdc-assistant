package com.ebcdc.assistant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_transitions")
public class WorkflowTransition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "document_id", nullable = false)
    private UUID documentId;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", nullable = false)
    private WorkflowState fromState;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false)
    private WorkflowState toState;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowAction action;
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false)
    private Role actorRole;
    private String comment;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public WorkflowTransition() {}
    public WorkflowTransition(UUID documentId, WorkflowState from, WorkflowState to,
                              WorkflowAction action, AppUser actor, String comment) {
        this.documentId = documentId; this.fromState = from; this.toState = to;
        this.action = action; this.actorId = actor.getId();
        this.actorRole = actor.getRole(); this.comment = comment;
    }

    public Long getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public WorkflowState getFromState() { return fromState; }
    public WorkflowState getToState() { return toState; }
    public WorkflowAction getAction() { return action; }
    public UUID getActorId() { return actorId; }
    public Role getActorRole() { return actorRole; }
    public String getComment() { return comment; }
    public Instant getOccurredAt() { return occurredAt; }
}
