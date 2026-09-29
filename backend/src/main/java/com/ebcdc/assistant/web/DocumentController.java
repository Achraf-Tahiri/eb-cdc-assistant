package com.ebcdc.assistant.web;

import com.ebcdc.assistant.domain.*;
import com.ebcdc.assistant.rag.RagVectorStore;
import com.ebcdc.assistant.repo.*;
import com.ebcdc.assistant.workflow.ForbiddenActionException;
import com.ebcdc.assistant.workflow.WorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class DocumentController {

    public record CreateDocumentRequest(String title, String docType,
                                        String projectCode, UUID providerId) {}
    public record VersionRequest(String content) {}
    public record ActionRequest(String action, String comment) {}
    public record ClarificationCreate(String question, String sectionRef) {}
    public record ClarificationResolve(String resolution) {}

    private static final Set<WorkflowState> STATES_VISIBLE_PRESTATAIRE = Set.of(
            WorkflowState.TRANSMIS_PRESTATAIRE, WorkflowState.CLARIFICATION_DEMANDEE,
            WorkflowState.IMPLEMENTATION_ACCEPTEE, WorkflowState.CLOTURE);
    private static final Set<WorkflowState> STATES_EDITABLE_METIER = Set.of(
            WorkflowState.BROUILLON, WorkflowState.CORRECTION_METIER,
            WorkflowState.CLARIFICATION_DEMANDEE);

    @Value("${app.rag.late-clarification-days}")
    private int lateDays;

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final ProviderRepository providers;
    private final WorkflowTransitionRepository transitions;
    private final ClarificationRequestRepository clarifications;
    private final RagJobRepository ragJobs;
    private final RagVectorStore vectorStore;
    private final WorkflowService workflowService;

    public DocumentController(DocumentRepository documents, DocumentVersionRepository versions,
                              ProviderRepository providers, WorkflowTransitionRepository transitions,
                              ClarificationRequestRepository clarifications,
                              RagJobRepository ragJobs, RagVectorStore vectorStore,
                              WorkflowService workflowService) {
        this.documents = documents;
        this.versions = versions;
        this.providers = providers;
        this.transitions = transitions;
        this.clarifications = clarifications;
        this.ragJobs = ragJobs;
        this.vectorStore = vectorStore;
        this.workflowService = workflowService;
    }

    private static AppUser me(Authentication auth) {
        return (AppUser) auth.getPrincipal();
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> listProviders() {
        return providers.findAll().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getId(), "name", p.getName(),
                        "contractRef", p.getContractRef() == null ? "" : p.getContractRef()))
                .toList();
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> listDocuments(Authentication auth) {
        AppUser user = me(auth);
        List<DocumentEntity> docs;
        if (user.getRole() == Role.PRESTATAIRE) {
            if (user.getProvider() == null) return List.of();
            docs = documents.findByProviderIdOrderByCreatedAtDesc(user.getProvider().getId())
                    .stream()
                    .filter(d -> STATES_VISIBLE_PRESTATAIRE.contains(d.getCurrentState()))
                    .toList();
        } else {
            docs = documents.findAllByOrderByCreatedAtDesc();
        }
        return docs.stream().map(this::summary).toList();
    }

    private Map<String, Object> summary(DocumentEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("title", d.getTitle());
        m.put("docType", d.getDocType().name());
        m.put("projectCode", d.getProjectCode());
        m.put("state", d.getCurrentState().name());
        m.put("provider", d.getProvider() != null ? d.getProvider().getName() : "");
        m.put("createdBy", d.getCreatedBy().getDisplayName());
        m.put("createdAt", d.getCreatedAt());
        m.put("lateClarificationDays", lateDays);
        return m;
    }

    @PostMapping("/documents")
    @Transactional
    public Map<String, Object> create(@RequestBody CreateDocumentRequest req, Authentication auth) {
        AppUser user = me(auth);
        if (user.getRole() != Role.METIER) {
            throw new ForbiddenActionException(user.getRole(), WorkflowAction.CREATE);
        }
        requireText(req.title(), "Le titre");
        requireText(req.docType(), "Le type de document");
        Provider provider = req.providerId() == null ? null
                : providers.findById(req.providerId()).orElseThrow();
        DocumentEntity doc = new DocumentEntity(req.title(),
                DocType.valueOf(req.docType()), req.projectCode(), provider, user);
        documents.save(doc);
        versions.save(new DocumentVersion(doc, 1,
                "# " + req.title() + "\n\n## Contexte\n\n## Périmètre\n\n" +
                "## Besoins fonctionnels\n\n## Contraintes\n\n" +
                "## Critères d'acceptation\n\n## Délais\n", user));
        workflowService.recordCreation(doc, user);
        return summary(doc);
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> get(@PathVariable UUID id, Authentication auth) {
        DocumentEntity doc = documents.findById(id).orElseThrow();
        checkVisibility(doc, me(auth));
        Map<String, Object> m = summary(doc);
        versions.findFirstByDocumentIdOrderByVersionNumDesc(id).ifPresent(v -> {
            m.put("content", v.getContent());
            m.put("versionNum", v.getVersionNum());
            m.put("versionAuthor", v.getAuthor().getDisplayName());
            m.put("versionAt", v.getCreatedAt());
        });
        m.put("versionCount", versions.findByDocumentIdOrderByVersionNumDesc(id).size());
        return m;
    }

    private void checkVisibility(DocumentEntity doc, AppUser user) {
        if (user.getRole() == Role.PRESTATAIRE) {
            boolean sameProvider = doc.getProvider() != null && user.getProvider() != null
                    && doc.getProvider().getId().equals(user.getProvider().getId());
            if (!sameProvider || !STATES_VISIBLE_PRESTATAIRE.contains(doc.getCurrentState())) {
                throw new NoSuchElementException("Document introuvable");
            }
        }
    }

    @PutMapping("/documents/{id}/versions")
    @Transactional
    public Map<String, Object> saveVersion(@PathVariable UUID id,
                                           @RequestBody VersionRequest req,
                                           Authentication auth) {
        AppUser user = me(auth);
        DocumentEntity doc = documents.findByIdForUpdate(id).orElseThrow();
        if (user.getRole() == Role.PRESTATAIRE) {
            throw new ForbiddenActionException(user.getRole(), WorkflowAction.SUBMIT);
        }
        if (user.getRole() == Role.METIER
                && !STATES_EDITABLE_METIER.contains(doc.getCurrentState())) {
            throw new IllegalStateException(
                    "Le document n'est pas modifiable dans l'état " + doc.getCurrentState());
        }
        if (req.content() == null) throw new IllegalArgumentException("Le contenu est obligatoire");
        int next = versions.findFirstByDocumentIdOrderByVersionNumDesc(id)
                .map(v -> v.getVersionNum() + 1).orElse(1);
        DocumentVersion v = versions.save(new DocumentVersion(doc, next, req.content(), user));
        return Map.of("versionNum", v.getVersionNum(), "createdAt", v.getCreatedAt());
    }

    /**
     * Suppression d'un brouillon jamais soumis.
     * Règle métier : un document entré dans le circuit de validation ne peut
     * jamais être supprimé (le journal d'audit doit rester complet pour
     * objectiver les délais devant le comité TMA). Un BROUILLON, lui, n'a
     * encore été vu par personne d'autre que son auteur : sa seule trace
     * d'audit est sa propre création. Le supprimer n'efface donc aucune
     * interaction entre acteurs.
     */
    @DeleteMapping("/documents/{id}")
    @Transactional
    public Map<String, Object> deleteDraft(@PathVariable UUID id, Authentication auth) {
        AppUser user = me(auth);
        DocumentEntity doc = documents.findByIdForUpdate(id).orElseThrow();
        if (user.getRole() != Role.METIER) {
            throw new ForbiddenActionException(
                    "Seul le rôle METIER peut supprimer un brouillon.");
        }
        if (!doc.getCreatedBy().getId().equals(user.getId())) {
            throw new ForbiddenActionException(
                    "Seul l'auteur du brouillon peut le supprimer.");
        }
        if (doc.getCurrentState() != WorkflowState.BROUILLON) {
            throw new IllegalStateException(
                    "Seuls les brouillons jamais soumis peuvent être supprimés. "
                    + "Un document entré dans le circuit de validation est conservé "
                    + "définitivement pour garantir la traçabilité (journal d'audit).");
        }
        // Nettoyage des données rattachées, puis du document lui-même.
        clarifications.deleteByDocumentId(id);   // vide par définition pour un brouillon
        ragJobs.deleteByDocumentId(id);          // analyses de complétude / suggestions
        vectorStore.deleteByDocumentId(id);      // chunks éventuels
        transitions.deleteByDocumentId(id);      // uniquement la ligne CREATE
        versions.deleteByDocumentId(id);
        documents.delete(doc);
        return Map.of("deleted", true);
    }

    @PostMapping("/documents/{id}/actions")
    public Map<String, Object> applyAction(@PathVariable UUID id,
                                           @RequestBody ActionRequest req,
                                           Authentication auth) {
        requireText(req.action(), "L’action");
        WorkflowState newState = workflowService.applyAction(
                id, WorkflowAction.valueOf(req.action()), me(auth), req.comment());
        return Map.of("state", newState.name());
    }

    @GetMapping("/documents/{id}/available-actions")
    public List<String> availableActions(@PathVariable UUID id, Authentication auth) {
        DocumentEntity doc = documents.findById(id).orElseThrow();
        AppUser user = me(auth);
        if (user.getRole() == Role.PRESTATAIRE) {
            boolean sameProvider = doc.getProvider() != null && user.getProvider() != null
                    && doc.getProvider().getId().equals(user.getProvider().getId());
            if (!sameProvider) return List.of();
        }
        return workflowService.availableActions(doc.getCurrentState(), user.getRole())
                .stream().map(Enum::name).toList();
    }

    @GetMapping("/documents/{id}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable UUID id, Authentication auth) {
        checkVisibility(documents.findById(id).orElseThrow(), me(auth));
        return transitions.findByDocumentIdOrderByOccurredAtAscIdAsc(id).stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("fromState", t.getFromState().name());
                    m.put("toState", t.getToState().name());
                    m.put("action", t.getAction().name());
                    m.put("actorRole", t.getActorRole().name());
                    m.put("comment", t.getComment());
                    m.put("occurredAt", t.getOccurredAt());
                    return m;
                }).toList();
    }

    // ----- Clarifications (le coeur du problème business) -----

    @GetMapping("/documents/{id}/clarifications")
    public List<Map<String, Object>> listClarifications(@PathVariable UUID id,
                                                        Authentication auth) {
        checkVisibility(documents.findById(id).orElseThrow(), me(auth));
        return clarifications.findByDocumentIdOrderByRequestedAtAsc(id).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("question", c.getQuestion());
                    m.put("sectionRef", c.getSectionRef());
                    m.put("requestedAt", c.getRequestedAt());
                    m.put("daysSinceProviderReceived", c.getDaysSinceProviderReceived());
                    m.put("resolvedAt", c.getResolvedAt());
                    m.put("resolution", c.getResolution());
                    m.put("late", c.getDaysSinceProviderReceived() != null
                            && c.getDaysSinceProviderReceived() > lateDays);
                    return m;
                }).toList();
    }

    @PostMapping("/documents/{id}/clarifications")
    @Transactional
    public Map<String, Object> createClarification(@PathVariable UUID id,
                                                   @RequestBody ClarificationCreate req,
                                                   Authentication auth) {
        AppUser user = me(auth);
        if (user.getRole() != Role.PRESTATAIRE || user.getProvider() == null) {
            throw new ForbiddenActionException(user.getRole(), WorkflowAction.REQUEST_CLARIFICATION);
        }
        requireText(req.question(), "La question");
        DocumentEntity doc = documents.findByIdForUpdate(id).orElseThrow();
        checkVisibility(doc, user);
        if (doc.getCurrentState() != WorkflowState.TRANSMIS_PRESTATAIRE
                && doc.getCurrentState() != WorkflowState.CLARIFICATION_DEMANDEE) {
            throw new IllegalStateException("Les clarifications sont fermées dans cet état");
        }

        // Jours écoulés depuis la dernière réception par le prestataire :
        // c'est la donnée clé de la détection des clarifications tardives (M2).
        Double daysSince = transitions
                .findFirstByDocumentIdAndToStateOrderByOccurredAtDesc(
                        id, WorkflowState.TRANSMIS_PRESTATAIRE)
                .map(t -> Duration.between(t.getOccurredAt(), Instant.now()).toMillis()
                        / 86_400_000.0)
                .map(d -> Math.round(d * 100.0) / 100.0)
                .orElse(null);

        ClarificationRequest c = clarifications.save(new ClarificationRequest(
                id, user.getProvider().getId(), user.getId(),
                req.question(), req.sectionRef(), daysSince));

        // Première question du cycle : le document passe en CLARIFICATION_DEMANDEE.
        // Questions suivantes dans le même cycle : simple ajout (détection M4).
        if (doc.getCurrentState() == WorkflowState.TRANSMIS_PRESTATAIRE) {
            workflowService.applyAction(id, WorkflowAction.REQUEST_CLARIFICATION, user,
                    "Clarification : " + abbreviate(req.question()));
        }
        return Map.of("id", c.getId(), "daysSinceProviderReceived",
                daysSince == null ? -1 : daysSince);
    }

    @PostMapping("/clarifications/{cid}/resolve")
    @Transactional
    public Map<String, Object> resolveClarification(@PathVariable UUID cid,
                                                    @RequestBody ClarificationResolve req,
                                                    Authentication auth) {
        AppUser user = me(auth);
        if (user.getRole() == Role.PRESTATAIRE) {
            throw new ForbiddenActionException(user.getRole(), WorkflowAction.RESOLVE);
        }
        requireText(req.resolution(), "La réponse");
        UUID documentId = clarifications.findDocumentIdById(cid).orElseThrow();
        // Toujours verrouiller le document avant de charger/modifier une question.
        DocumentEntity doc = documents.findByIdForUpdate(documentId).orElseThrow();
        ClarificationRequest c = clarifications.findById(cid).orElseThrow();
        if (c.getResolvedAt() != null) {
            throw new IllegalStateException("Cette clarification est déjà résolue");
        }
        c.resolve(req.resolution());
        clarifications.save(c);

        // Dernière clarification ouverte résolue : le document repart chez le prestataire.
        boolean allResolved =
                clarifications.countByDocumentIdAndResolvedAtIsNull(c.getDocumentId()) == 0;
        if (allResolved && doc.getCurrentState() == WorkflowState.CLARIFICATION_DEMANDEE) {
            workflowService.applyAction(c.getDocumentId(), WorkflowAction.RESOLVE, user,
                    "Réponse : " + abbreviate(req.resolution()));
        }
        return Map.of("resolved", true, "documentReturned", allResolved);
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " est obligatoire");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
