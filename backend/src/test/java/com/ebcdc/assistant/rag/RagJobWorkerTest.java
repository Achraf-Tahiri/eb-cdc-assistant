package com.ebcdc.assistant.rag;
import com.ebcdc.assistant.domain.*;
import com.ebcdc.assistant.repo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RagJobWorkerTest {
    @Test void failedModelCallBecomesFailedJobAndWorkerCanContinue() {
        var jobs=mock(RagJobRepository.class);
        var versions=mock(DocumentVersionRepository.class);
        var ollama=mock(OllamaClient.class);
        var chunks=new ChunkingService();
        var worker=new RagJobWorker(jobs,versions,ollama,chunks,new CompletenessChecker(chunks),mock(RagVectorStore.class),6,0.55);
        var author = new AppUser("metier@test.fr", "Métier", Role.METIER, "unused", null);
        var document = new DocumentEntity("Export", DocType.EB, "TEST", null, author);
        var failed=new RagJob(document.getId(),JobType.SUGGESTION,"{\"sectionTitle\":\"Contexte\",\"draft\":\"Texte\"}");
        when(jobs.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING)).thenReturn(Optional.of(failed),Optional.empty());
        when(versions.findFirstByDocumentIdOrderByVersionNumDesc(document.getId()))
                .thenReturn(Optional.of(new DocumentVersion(document, 1, "## Contexte\nTexte", author)));
        when(ollama.embedOne(anyString())).thenThrow(new IllegalStateException("Ollama indisponible"));
        worker.poll();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getError()).contains("Ollama indisponible");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThatCode(worker::poll).doesNotThrowAnyException();
        verify(jobs,times(2)).save(failed);
    }

    @Test void completenessUsesWholeDocumentOnceAndValidatesTheResult() throws Exception {
        var jobs = mock(RagJobRepository.class);
        var versions = mock(DocumentVersionRepository.class);
        var ollama = mock(OllamaClient.class);
        var chunks = new ChunkingService();
        var worker = new RagJobWorker(jobs, versions, ollama, chunks,
                new CompletenessChecker(chunks), mock(RagVectorStore.class), 6, 0.55);

        var author = new AppUser("metier@test.fr", "Métier", Role.METIER, "unused", null);
        var document = new DocumentEntity("Export", DocType.EB, "TEST", null, author);
        var version = new DocumentVersion(document, 2, """
                # Export

                ## Besoins fonctionnels
                Le comptable télécharge les factures.

                ## Contraintes
                Le fichier est un CSV UTF-8.
                """, author);
        var job = new RagJob(document.getId(), JobType.COMPLETENESS_CHECK, null);

        when(jobs.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.of(job));
        when(versions.findFirstByDocumentIdOrderByVersionNumDesc(document.getId()))
                .thenReturn(Optional.of(version));
        when(ollama.generateJson(anyString())).thenReturn("""
                {
                  "sections": [
                    {
                      "titre": "1. Besoins fonctionnels",
                      "points_forts": ["L'acteur et l'action sont identifiés."],
                      "ambiguites": [],
                      "informations_manquantes": [],
                      "questions_previsibles_prestataire": [],
                      "score_completude": 8,
                      "justification_score": "La section est claire grâce au format précisé ailleurs."
                    },
                    {
                      "titre": "Contraintes",
                      "points_forts": ["Le format et l'encodage sont précisés."],
                      "ambiguites": [],
                      "informations_manquantes": [],
                      "questions_previsibles_prestataire": [],
                      "score_completude": 9,
                      "justification_score": "La contrainte est précise et exploitable."
                    }
                  ]
                }
                """);

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ollama, times(1)).generateJson(prompt.capture());
        assertThat(prompt.getValue())
                .contains("Le comptable télécharge les factures.")
                .contains("Le fichier est un CSV UTF-8.")
                .contains("Une information présente dans une autre section n'est PAS manquante")
                .contains("7 à 8 : section claire et exploitable");

        var result = new ObjectMapper().readTree(job.getResult());
        assertThat(result.path("analyse_globale").asBoolean()).isTrue();
        assertThat(result.path("bareme_version").asInt()).isEqualTo(1);
        assertThat(result.path("sections_incompletes").asInt()).isZero();
        assertThat(result.path("sections").size()).isEqualTo(2);
        assertThat(result.path("sections").get(0).path("analyse")
                .path("justification_score").asText()).contains("format précisé ailleurs");
    }

    @Test void malformedCompletenessResponseFailsClearly() {
        var jobs = mock(RagJobRepository.class);
        var versions = mock(DocumentVersionRepository.class);
        var ollama = mock(OllamaClient.class);
        var chunks = new ChunkingService();
        var worker = new RagJobWorker(jobs, versions, ollama, chunks,
                new CompletenessChecker(chunks), mock(RagVectorStore.class), 6, 0.55);
        var author = new AppUser("metier@test.fr", "Métier", Role.METIER, "unused", null);
        var document = new DocumentEntity("Export", DocType.EB, "TEST", null, author);
        var version = new DocumentVersion(document, 1, "## Contexte\nTexte", author);
        var job = new RagJob(document.getId(), JobType.COMPLETENESS_CHECK, null);
        when(jobs.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.of(job));
        when(versions.findFirstByDocumentIdOrderByVersionNumDesc(document.getId()))
                .thenReturn(Optional.of(version));
        when(ollama.generateJson(anyString())).thenReturn("{\"message\":\"réponse incomplète\"}");

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getError()).contains("liste des sections attendue");
    }

    @Test void suggestionUsesWholeDocumentAndOnlyKeepsVerifiedSources() throws Exception {
        var jobs = mock(RagJobRepository.class);
        var versions = mock(DocumentVersionRepository.class);
        var ollama = mock(OllamaClient.class);
        var vectorStore = mock(RagVectorStore.class);
        var chunks = new ChunkingService();
        var worker = new RagJobWorker(jobs, versions, ollama, chunks,
                new CompletenessChecker(chunks), vectorStore, 6, 0.55);
        var author = new AppUser("metier@test.fr", "Métier", Role.METIER, "unused", null);
        var document = new DocumentEntity("Export", DocType.EB, "TEST", null, author);
        var version = new DocumentVersion(document, 3, """
                ## Contexte
                Le traitement manuel crée des erreurs.
                ## Contraintes
                Le fichier doit être encodé en UTF-8.
                """, author);
        var job = new RagJob(document.getId(), JobType.SUGGESTION,
                "{\"sectionTitle\":\"Besoins fonctionnels\",\"draft\":\"Exporter les factures\"}");

        when(jobs.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING)).thenReturn(Optional.of(job));
        when(versions.findFirstByDocumentIdOrderByVersionNumDesc(document.getId()))
                .thenReturn(Optional.of(version));
        when(ollama.embedOne(anyString())).thenReturn(List.of(0.1, 0.2));
        when(vectorStore.searchTopK(anyList(), eq(8))).thenReturn(List.of(
                Map.of("source_label", "Ancien CDC export",
                        "section_title", "Format",
                        "chunk_text", "Le fichier contient une ligne d'en-tête.",
                        "score", new BigDecimal("0.7123")),
                Map.of("source_label", "Ancien CDC encodage",
                        "section_title", "Contraintes",
                        "chunk_text", "Le fichier doit être encodé en UTF-8.",
                        "score", new BigDecimal("0.6900"))));
        when(ollama.generateJson(anyString())).thenReturn("""
                {"suggestions":[
                  {"titre":"Préciser l'en-tête","texte":"Le fichier exporté contient une ligne d'en-tête.","source_ids":[1]},
                  {"titre":"Référence inventée","texte":"Le transfert utilise une API externe sécurisée.","source_ids":[99]},
                  {"titre":"Sans référence","texte":"Ajouter une nouvelle règle sans preuve suffisante.","source_ids":[]},
                  {"titre":"Source sans rapport","texte":"Le transfert utilise une API externe sécurisée.","source_ids":[1]},
                  {"titre":"Simple répétition","texte":"Le fichier doit être encodé en UTF-8.","source_ids":[2]}
                ]}
                """);

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ollama).generateJson(prompt.capture());
        assertThat(prompt.getValue())
                .contains("Le traitement manuel crée des erreurs.")
                .contains("Le fichier doit être encodé en UTF-8.")
                .contains("N'invente aucun acteur, logiciel, API")
                .contains("source_ids");
        var result = new ObjectMapper().readTree(job.getResult());
        assertThat(result.path("document_entier_pris_en_compte").asBoolean()).isTrue();
        assertThat(result.path("version_analysee").asInt()).isEqualTo(3);
        assertThat(result.path("resultat").path("suggestions")).hasSize(1);
        assertThat(result.path("resultat").path("suggestions").get(0)
                .path("sources").get(0).path("label").asText()).isEqualTo("Ancien CDC export");
    }

    @Test void suggestionStopsWhenCorpusHasNoRelevantSource() throws Exception {
        var jobs = mock(RagJobRepository.class);
        var versions = mock(DocumentVersionRepository.class);
        var ollama = mock(OllamaClient.class);
        var vectorStore = mock(RagVectorStore.class);
        var chunks = new ChunkingService();
        var worker = new RagJobWorker(jobs, versions, ollama, chunks,
                new CompletenessChecker(chunks), vectorStore, 6, 0.55);
        var author = new AppUser("metier@test.fr", "Métier", Role.METIER, "unused", null);
        var document = new DocumentEntity("Export", DocType.EB, "TEST", null, author);
        var version = new DocumentVersion(document, 1, "## Contexte\nUn besoin métier.", author);
        var job = new RagJob(document.getId(), JobType.SUGGESTION,
                "{\"sectionTitle\":\"Délais\",\"draft\":\"Livraison en juin\"}");

        when(jobs.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING)).thenReturn(Optional.of(job));
        when(versions.findFirstByDocumentIdOrderByVersionNumDesc(document.getId()))
                .thenReturn(Optional.of(version));
        when(ollama.embedOne(anyString())).thenReturn(List.of(0.1, 0.2));
        when(vectorStore.searchTopK(anyList(), eq(8))).thenReturn(List.of(Map.of(
                "source_label", "Document sans rapport",
                "section_title", "Couleurs",
                "chunk_text", "La page utilise du bleu.",
                "score", new BigDecimal("0.4100"))));

        worker.poll();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        verify(ollama, never()).generateJson(anyString());
        var result = new ObjectMapper().readTree(job.getResult());
        assertThat(result.path("resultat").path("suggestions")).isEmpty();
        assertThat(result.path("resultat").path("message").asText())
                .contains("Aucune source du corpus");
    }
}
