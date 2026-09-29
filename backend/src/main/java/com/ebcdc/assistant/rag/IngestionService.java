package com.ebcdc.assistant.rag;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingestion des anciens documents EB/CDC dans la base vectorielle :
 * extraction texte (Tika) -> découpage sections + chunks -> embeddings par lot -> pgvector.
 */
@Service
public class IngestionService {

    private static final int EMBED_BATCH_SIZE = 16;
    private static final int MAX_CHUNKS_PER_DOC = 300;

    private final ChunkingService chunkingService;
    private final OllamaClient ollamaClient;
    private final RagVectorStore vectorStore;
    private final Tika tika = new Tika();

    public IngestionService(ChunkingService chunkingService,
                            OllamaClient ollamaClient,
                            RagVectorStore vectorStore) {
        this.chunkingService = chunkingService;
        this.ollamaClient = ollamaClient;
        this.vectorStore = vectorStore;
        this.tika.setMaxStringLength(2_000_000);
    }

    public int ingestFile(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream()) {
            String text = tika.parseToString(in);
            return ingestText(file.getOriginalFilename(), text);
        }
    }

    @Transactional
    public int ingestText(String label, String content) {
        String normalizedLabel = label == null || label.isBlank()
                ? "Document sans nom" : label.trim();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Le document à importer est vide");
        }
        record LabeledChunk(String sectionTitle, String text) {}
        List<LabeledChunk> all = new ArrayList<>();
        for (ChunkingService.Section section : chunkingService.splitSections(content)) {
            for (String chunk : chunkingService.chunk(section.body())) {
                all.add(new LabeledChunk(section.title(), chunk));
                if (all.size() >= MAX_CHUNKS_PER_DOC) break;
            }
            if (all.size() >= MAX_CHUNKS_PER_DOC) break;
        }
        List<List<Double>> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < all.size(); i += EMBED_BATCH_SIZE) {
            List<LabeledChunk> batch = all.subList(i, Math.min(i + EMBED_BATCH_SIZE, all.size()));
            allEmbeddings.addAll(ollamaClient.embed(batch.stream().map(LabeledChunk::text).toList()));
        }

        // Réimporter le même document remplace son ancienne version. La transaction
        // restaure les anciens extraits si une insertion échoue.
        vectorStore.deleteBySourceLabel(normalizedLabel);
        for (int i = 0; i < all.size(); i++) {
            vectorStore.insertChunk(null, normalizedLabel, all.get(i).sectionTitle(),
                    all.get(i).text(), allEmbeddings.get(i));
        }
        return all.size();
    }
}
