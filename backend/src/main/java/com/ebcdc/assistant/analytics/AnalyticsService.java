package com.ebcdc.assistant.analytics;

import com.ebcdc.assistant.domain.JobStatus;
import com.ebcdc.assistant.domain.JobType;
import com.ebcdc.assistant.domain.WorkflowAction;
import com.ebcdc.assistant.repo.ClarificationRequestRepository;
import com.ebcdc.assistant.repo.DocumentRepository;
import com.ebcdc.assistant.repo.RagJobRepository;
import com.ebcdc.assistant.repo.WorkflowTransitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Métriques d'audit pour la détection des patterns de ralentissement.
 * Toutes calculées sur le journal workflow_transitions (écriture seule)
 * et sur clarification_requests. Ce sont des indicateurs d'objectivation
 * des délais, pas des preuves d'intention.
 */
@Service
public class AnalyticsService {

    private final JdbcTemplate jdbc;
    private final DocumentRepository documentRepository;
    private final WorkflowTransitionRepository transitionRepository;
    private final ClarificationRequestRepository clarificationRepository;
    private final RagJobRepository ragJobRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int lateDays;

    public AnalyticsService(JdbcTemplate jdbc,
                            DocumentRepository documentRepository,
                            WorkflowTransitionRepository transitionRepository,
                            ClarificationRequestRepository clarificationRepository,
                            RagJobRepository ragJobRepository,
                            @Value("${app.rag.late-clarification-days}") int lateDays) {
        this.jdbc = jdbc;
        this.documentRepository = documentRepository;
        this.transitionRepository = transitionRepository;
        this.clarificationRepository = clarificationRepository;
        this.ragJobRepository = ragJobRepository;
        this.lateDays = lateDays;
    }

    /** M1 à M4, agrégées par prestataire. */
    public List<Map<String, Object>> providerMetrics() {
        String sql = """
            WITH t AS (
                SELECT wt.document_id, wt.to_state, wt.action, wt.occurred_at,
                       lead(wt.occurred_at) OVER (
                           PARTITION BY wt.document_id
                           ORDER BY wt.occurred_at, wt.id) AS next_at
                FROM workflow_transitions wt
            ),
            prov_time AS (
                SELECT d.provider_id,
                       avg(EXTRACT(EPOCH FROM (COALESCE(t.next_at, now()) - t.occurred_at)) / 86400.0)
                           AS avg_days_waiting
                FROM t JOIN documents d ON d.id = t.document_id
                WHERE t.to_state = 'TRANSMIS_PRESTATAIRE'
                GROUP BY d.provider_id
            ),
            cycles AS (
                SELECT d.provider_id,
                       count(*) AS n_cycles,
                       count(DISTINCT d.id) AS n_docs_with_cycles
                FROM t JOIN documents d ON d.id = t.document_id
                WHERE t.action = 'REQUEST_CLARIFICATION'
                GROUP BY d.provider_id
            ),
            questions AS (
                SELECT provider_id,
                       count(*) AS n_questions,
                       count(*) FILTER (WHERE days_since_provider_received > ?) AS n_late
                FROM clarification_requests
                GROUP BY provider_id
            ),
            docs AS (
                SELECT provider_id, count(*) AS n_docs
                FROM documents WHERE provider_id IS NOT NULL
                GROUP BY provider_id
            )
            SELECT p.id AS provider_id,
                   p.name,
                   p.contract_ref,
                   COALESCE(dk.n_docs, 0) AS nb_documents,
                   round(COALESCE(pt.avg_days_waiting, 0)::numeric, 2) AS m1_attente_moyenne_jours,
                   CASE WHEN COALESCE(q.n_questions, 0) = 0 THEN 0
                        ELSE round(100.0 * q.n_late / q.n_questions, 1)
                   END AS m2_taux_tardif_pct,
                   CASE WHEN COALESCE(dk.n_docs, 0) = 0 THEN 0
                        ELSE round(COALESCE(c.n_cycles, 0)::numeric / dk.n_docs, 2)
                   END AS m3_cycles_par_document,
                   CASE WHEN COALESCE(q.n_questions, 0) = 0 THEN NULL
                        ELSE round(COALESCE(c.n_cycles, 0)::numeric / q.n_questions, 2)
                   END AS m4_ratio_cycles_questions,
                   COALESCE(q.n_questions, 0) AS total_questions,
                   COALESCE(c.n_cycles, 0) AS total_cycles
            FROM providers p
            LEFT JOIN prov_time pt ON pt.provider_id = p.id
            LEFT JOIN cycles c ON c.provider_id = p.id
            LEFT JOIN questions q ON q.provider_id = p.id
            LEFT JOIN docs dk ON dk.provider_id = p.id
            ORDER BY p.name
            """;
        return jdbc.queryForList(sql, lateDays);
    }

    /** M5 : décomposition du temps par acteur pour un document. */
    public List<Map<String, Object>> documentBreakdown(UUID documentId) {
        String sql = """
            WITH t AS (
                SELECT wt.to_state, wt.occurred_at,
                       lead(wt.occurred_at) OVER (ORDER BY wt.occurred_at, wt.id) AS next_at
                FROM workflow_transitions wt
                WHERE wt.document_id = ?
            )
            SELECT CASE
                     WHEN to_state IN ('BROUILLON', 'CORRECTION_METIER') THEN 'METIER'
                     WHEN to_state IN ('SOUMIS_PROJET', 'VALIDE_PROJET') THEN 'PROJET'
                     WHEN to_state = 'TRANSMIS_PRESTATAIRE' THEN 'PRESTATAIRE'
                     WHEN to_state = 'IMPLEMENTATION_ACCEPTEE' THEN 'PRESTATAIRE (implémentation)'
                     WHEN to_state = 'CLARIFICATION_DEMANDEE' THEN 'PROJET_METIER (clarifications)'
                     ELSE 'AUTRE'
                   END AS acteur,
                   round(sum(EXTRACT(EPOCH FROM (COALESCE(next_at, now()) - occurred_at)) / 86400.0)::numeric, 2)
                       AS jours
            FROM t
            WHERE to_state <> 'CLOTURE'
            GROUP BY 1
            ORDER BY jours DESC
            """;
        return jdbc.queryForList(sql, documentId);
    }

    /**
     * M6 : corrélation score de complétude <-> cycles de clarification, par document.
     * Comparaison descriptive : aucune preuve de causalité ni d’absence d’ambiguïté.
     */
    public List<Map<String, Object>> completenessCorrelation() {
        List<Map<String, Object>> rows = new ArrayList<>();
        documentRepository.findAllByOrderByCreatedAtDesc().forEach(doc -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentId", doc.getId());
            row.put("titre", doc.getTitle());
            row.put("prestataire", doc.getProvider() != null ? doc.getProvider().getName() : "");
            row.put("etat", doc.getCurrentState().name());
            row.put("cycles_clarification", transitionRepository
                    .countByDocumentIdAndAction(doc.getId(), WorkflowAction.REQUEST_CLARIFICATION));
            row.put("questions", clarificationRepository.countByDocumentId(doc.getId()));
            row.put("score_completude", latestCompletenessScore(doc.getId()));
            rows.add(row);
        });
        return rows;
    }

    /**
     * Alertes actionnables pour l'équipe projet :
     * document dormant chez le prestataire, clarification tardive, cycles répétés.
     */
    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> out = new ArrayList<>();

        jdbc.queryForList("""
            SELECT d.id AS document_id, d.title AS titre, p.name AS prestataire,
                   round((EXTRACT(EPOCH FROM (now() - last.occurred_at)) / 86400.0)::numeric, 1)
                       AS jours
            FROM documents d
            JOIN providers p ON p.id = d.provider_id
            JOIN LATERAL (
                SELECT max(occurred_at) AS occurred_at
                FROM workflow_transitions wt WHERE wt.document_id = d.id
            ) last ON true
            WHERE d.current_state = 'TRANSMIS_PRESTATAIRE'
              AND last.occurred_at < now() - make_interval(days => ?)
            ORDER BY last.occurred_at ASC
            """, lateDays).forEach(r -> { r.put("type", "DOCUMENT_DORMANT"); out.add(r); });

        jdbc.queryForList("""
            SELECT c.document_id, d.title AS titre, p.name AS prestataire,
                   round(c.days_since_provider_received::numeric, 1) AS jours,
                   c.question
            FROM clarification_requests c
            JOIN documents d ON d.id = c.document_id
            JOIN providers p ON p.id = c.provider_id
            WHERE c.days_since_provider_received > ?
            ORDER BY c.requested_at DESC
            LIMIT 20
            """, lateDays).forEach(r -> { r.put("type", "CLARIFICATION_TARDIVE"); out.add(r); });

        jdbc.queryForList("""
            SELECT d.id AS document_id, d.title AS titre, p.name AS prestataire,
                   count(*) AS cycles
            FROM workflow_transitions wt
            JOIN documents d ON d.id = wt.document_id
            JOIN providers p ON p.id = d.provider_id
            WHERE wt.action = 'REQUEST_CLARIFICATION'
            GROUP BY d.id, d.title, p.name
            HAVING count(*) >= 3
            ORDER BY count(*) DESC
            """).forEach(r -> { r.put("type", "CYCLES_REPETES"); out.add(r); });

        return out;
    }

    private Double latestCompletenessScore(UUID documentId) {
        return ragJobRepository
                .findFirstByDocumentIdAndJobTypeAndStatusOrderByFinishedAtDesc(
                        documentId, JobType.COMPLETENESS_CHECK, JobStatus.DONE)
                .map(job -> {
                    try {
                        JsonNode root = mapper.readTree(job.getResult());
                        JsonNode sections = root.path("sections");
                        double sum = 0;
                        int n = 0;
                        for (JsonNode s : sections) {
                            JsonNode score = s.path("analyse").path("score_completude");
                            if (score.isNumber()) { sum += score.asDouble(); n++; }
                        }
                        return n == 0 ? null : Math.round(sum / n * 10.0) / 10.0;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }
}
