package com.ebcdc.assistant.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Accès pgvector via JDBC (le type vector n'est pas mappé en JPA).
 * Le littéral vecteur est passé sous la forme '[x1,x2,...]' casté en vector.
 */
@Repository
public class RagVectorStore {

    private final JdbcTemplate jdbc;

    public RagVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(d -> Float.toString(d.floatValue()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    public void insertChunk(UUID documentId, String sourceLabel, String sectionTitle,
                            String chunkText, List<Double> embedding) {
        jdbc.update("""
                INSERT INTO rag_chunks (document_id, source_label, section_title, chunk_text, embedding)
                VALUES (?, ?, ?, ?, CAST(? AS vector))
                """,
                documentId, sourceLabel, sectionTitle, chunkText, toVectorLiteral(embedding));
    }

    public List<Map<String, Object>> searchTopK(List<Double> queryEmbedding, int k) {
        String vec = toVectorLiteral(queryEmbedding);
        return jdbc.queryForList("""
                SELECT chunk_text, source_label, section_title,
                       round((1 - (embedding <=> CAST(? AS vector)))::numeric, 4) AS score
                FROM rag_chunks
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """, vec, vec, k);
    }

    public void deleteByDocumentId(UUID documentId) {
        jdbc.update("DELETE FROM rag_chunks WHERE document_id = ?", documentId);
    }

    public void deleteBySourceLabel(String sourceLabel) {
        jdbc.update("DELETE FROM rag_chunks WHERE source_label = ?", sourceLabel);
    }

    public long countChunks() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM rag_chunks", Long.class);
        return n == null ? 0 : n;
    }
}
