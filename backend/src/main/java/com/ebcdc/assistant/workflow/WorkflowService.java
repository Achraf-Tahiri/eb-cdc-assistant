package com.ebcdc.assistant.workflow;

import com.ebcdc.assistant.domain.*;
import com.ebcdc.assistant.repo.DocumentRepository;
import com.ebcdc.assistant.repo.ClarificationRequestRepository;
import com.ebcdc.assistant.repo.WorkflowTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static com.ebcdc.assistant.domain.Role.*;
import static com.ebcdc.assistant.domain.WorkflowAction.*;
import static com.ebcdc.assistant.domain.WorkflowState.*;

/**
 * Machine à états du workflow EB/CDC.
 * Table de transitions (état courant, action) -> (état cible, rôles autorisés).
 * Chaque transition est journalisée dans workflow_transitions au sein de la
 * même transaction que le changement d'état (audit fiable).
 */
@Service
public class WorkflowService {

    public record StateAction(WorkflowState state, WorkflowAction action) {}
    public record TransitionRule(WorkflowState target, Set<Role> allowedRoles) {}

    private static final Map<StateAction, TransitionRule> RULES = Map.ofEntries(
        Map.entry(new StateAction(BROUILLON, SUBMIT),
                  new TransitionRule(SOUMIS_PROJET, Set.of(METIER))),
        Map.entry(new StateAction(SOUMIS_PROJET, VALIDATE),
                  new TransitionRule(VALIDE_PROJET, Set.of(PROJET))),
        Map.entry(new StateAction(SOUMIS_PROJET, REQUEST_CORRECTION),
                  new TransitionRule(CORRECTION_METIER, Set.of(PROJET))),
        Map.entry(new StateAction(CORRECTION_METIER, SUBMIT),
                  new TransitionRule(SOUMIS_PROJET, Set.of(METIER))),
        Map.entry(new StateAction(VALIDE_PROJET, SEND),
                  new TransitionRule(TRANSMIS_PRESTATAIRE, Set.of(PROJET))),
        Map.entry(new StateAction(TRANSMIS_PRESTATAIRE, REQUEST_CLARIFICATION),
                  new TransitionRule(CLARIFICATION_DEMANDEE, Set.of(PRESTATAIRE))),
        Map.entry(new StateAction(TRANSMIS_PRESTATAIRE, ACCEPT),
                  new TransitionRule(IMPLEMENTATION_ACCEPTEE, Set.of(PRESTATAIRE))),
        Map.entry(new StateAction(CLARIFICATION_DEMANDEE, RESOLVE),
                  new TransitionRule(TRANSMIS_PRESTATAIRE, Set.of(PROJET, METIER))),
        Map.entry(new StateAction(IMPLEMENTATION_ACCEPTEE, CLOSE),
                  new TransitionRule(CLOTURE, Set.of(PROJET)))
    );

    private final ClarificationRequestRepository clarifications;
    private final DocumentRepository documentRepository;
    private final WorkflowTransitionRepository transitionRepository;

    public WorkflowService(DocumentRepository documentRepository,
                           WorkflowTransitionRepository transitionRepository,
                           ClarificationRequestRepository clarifications) {
        this.clarifications = clarifications;
        this.documentRepository = documentRepository;
        this.transitionRepository = transitionRepository;
    }

    /** Ligne d'audit initiale à la création (permet de mesurer le temps en BROUILLON). */
    @Transactional
    public void recordCreation(DocumentEntity doc, AppUser actor) {
        transitionRepository.save(new WorkflowTransition(
                doc.getId(), BROUILLON, BROUILLON, CREATE, actor, "Création du document"));
    }

    @Transactional
    public WorkflowState applyAction(UUID documentId, WorkflowAction action,
                                     AppUser actor, String comment) {
        DocumentEntity doc = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document introuvable"));

        TransitionRule rule = RULES.get(new StateAction(doc.getCurrentState(), action));
        if (rule == null) {
            throw new InvalidTransitionException(doc.getCurrentState(), action);
        }
        if (!rule.allowedRoles().contains(actor.getRole())) {
            throw new ForbiddenActionException(actor.getRole(), action);
        }
        // Un prestataire ne peut agir que sur les documents de son propre contrat TMA
        if (actor.getRole() == PRESTATAIRE) {
            if (doc.getProvider() == null || actor.getProvider() == null
                    || !doc.getProvider().getId().equals(actor.getProvider().getId())) {
                throw new ForbiddenActionException(actor.getRole(), action);
            }
        }

        if (action == SEND && doc.getProvider() == null) {
            throw new IllegalStateException("Choisissez un prestataire avant la transmission");
        }
        if ((action == RESOLVE || action == ACCEPT)
                && clarifications.countByDocumentIdAndResolvedAtIsNull(documentId) > 0) {
            throw new IllegalStateException("Répondez à toutes les clarifications avant de continuer");
        }
        if (action == REQUEST_CLARIFICATION
                && clarifications.countByDocumentIdAndResolvedAtIsNull(documentId) == 0) {
            throw new IllegalArgumentException("Une question est obligatoire pour demander une clarification");
        }

        transitionRepository.save(new WorkflowTransition(
                documentId, doc.getCurrentState(), rule.target(), action, actor, comment));
        doc.setCurrentState(rule.target());
        return rule.target();
    }

    /** Actions possibles pour un état et un rôle donnés (pour piloter l'interface). */
    public List<WorkflowAction> availableActions(WorkflowState state, Role role) {
        return RULES.entrySet().stream()
                .filter(e -> e.getKey().state() == state
                          && e.getValue().allowedRoles().contains(role))
                .map(e -> e.getKey().action())
                .sorted()
                .toList();
    }
}
