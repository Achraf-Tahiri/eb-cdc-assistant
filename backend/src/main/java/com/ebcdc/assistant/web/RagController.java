package com.ebcdc.assistant.web;

import com.ebcdc.assistant.domain.AppUser;
import com.ebcdc.assistant.domain.JobType;
import com.ebcdc.assistant.domain.RagJob;
import com.ebcdc.assistant.domain.Role;
import com.ebcdc.assistant.domain.WorkflowAction;
import com.ebcdc.assistant.rag.IngestionService;
import com.ebcdc.assistant.rag.OllamaClient;
import com.ebcdc.assistant.rag.RagVectorStore;
import com.ebcdc.assistant.repo.RagJobRepository;
import com.ebcdc.assistant.workflow.ForbiddenActionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le corpus RAG contient les documents internes historiques :
 * l'ensemble des endpoints est réservé aux équipes métier et projet.
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    public record CreateJobRequest(UUID documentId, String jobType,
                                   String sectionTitle, String draft) {}
    public record SearchRequest(String query) {}
    public record IngestTextRequest(String label, String content) {}

    private final com.ebcdc.assistant.repo.DocumentRepository documents;
    private final RagJobRepository jobs;
    private final IngestionService ingestionService;
    private final OllamaClient ollamaClient;
    private final RagVectorStore vectorStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagController(RagJobRepository jobs, IngestionService ingestionService,
                         OllamaClient ollamaClient, RagVectorStore vectorStore,
                         com.ebcdc.assistant.repo.DocumentRepository documents) {
        this.documents = documents;
        this.jobs = jobs;
        this.ingestionService = ingestionService;
        this.ollamaClient = ollamaClient;
        this.vectorStore = vectorStore;
    }

    private static void internalOnly(Authentication auth) {
        AppUser user = (AppUser) auth.getPrincipal();
        if (user.getRole() == Role.PRESTATAIRE) {
            throw new ForbiddenActionException(user.getRole(), WorkflowAction.REQUEST_CLARIFICATION);
        }
    }

    @PostMapping("/jobs")
    public Map<String, Object> createJob(@RequestBody CreateJobRequest req,
                                         Authentication auth) throws Exception {
        internalOnly(auth);
        if (req.documentId() == null || req.jobType() == null) {
            throw new IllegalArgumentException("Document et type de job obligatoires");
        }
        if (!documents.existsById(req.documentId())) {
            throw new java.util.NoSuchElementException("Document introuvable");
        }
        JobType type = JobType.valueOf(req.jobType());
        String inputRef = null;
        if (type == JobType.SUGGESTION) {
            inputRef = mapper.writeValueAsString(Map.of(
                    "sectionTitle", req.sectionTitle() == null ? "Section" : req.sectionTitle(),
                    "draft", req.draft() == null ? "" : req.draft()));
        }
        RagJob job = jobs.save(new RagJob(req.documentId(), type, inputRef));
        return Map.of("id", job.getId(), "status", job.getStatus().name());
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> getJob(@PathVariable UUID id, Authentication auth) {
        internalOnly(auth);
        RagJob job = jobs.findById(id).orElseThrow();
        return jobView(job);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs(@RequestParam UUID documentId,
                                              Authentication auth) {
        internalOnly(auth);
        return jobs.findByDocumentIdOrderByCreatedAtDesc(documentId)
                .stream().map(this::jobView).toList();
    }

    private Map<String, Object> jobView(RagJob job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.getId());
        m.put("documentId", job.getDocumentId());
        m.put("jobType", job.getJobType().name());
        m.put("status", job.getStatus().name());
        m.put("createdAt", job.getCreatedAt());
        m.put("finishedAt", job.getFinishedAt());
        m.put("error", job.getError());
        // Pour une suggestion, le frontend a besoin de savoir quelle section
        // était visée afin de pouvoir proposer l'insertion automatique.
        if (job.getJobType() == JobType.SUGGESTION && job.getInputRef() != null) {
            try {
                m.put("sectionTitle", mapper.readTree(job.getInputRef())
                        .path("sectionTitle").asText(null));
            } catch (Exception ignored) { }
        }
        try {
            m.put("result", job.getResult() == null ? null : mapper.readTree(job.getResult()));
        } catch (Exception e) {
            m.put("result", job.getResult());
        }
        return m;
    }

    @GetMapping("/corpus")
    public Map<String, Object> corpus(Authentication auth) {
        internalOnly(auth);
        return Map.of("totalChunks", vectorStore.countChunks());
    }

    @PostMapping("/search")
    public List<Map<String, Object>> search(@RequestBody SearchRequest req,
                                            Authentication auth) {
        internalOnly(auth);
        return vectorStore.searchTopK(ollamaClient.embedOne(req.query()), 5);
    }

    @PostMapping("/ingest-text")
    public Map<String, Object> ingestText(@RequestBody IngestTextRequest req,
                                          Authentication auth) {
        internalOnly(auth);
        int n = ingestionService.ingestText(req.label(), req.content());
        return Map.of("chunksInserted", n, "totalChunks", vectorStore.countChunks());
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingestFile(@RequestParam("file") MultipartFile file,
                                          Authentication auth) throws Exception {
        internalOnly(auth);
        int n = ingestionService.ingestFile(file);
        return Map.of("chunksInserted", n, "totalChunks", vectorStore.countChunks());
    }
}
