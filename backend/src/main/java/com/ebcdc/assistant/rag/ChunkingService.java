package com.ebcdc.assistant.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Découpage des documents EB/CDC :
 * 1. par sections (titres Markdown), pour conserver le contexte métier ;
 * 2. par fenêtres de ~1200 caractères avec chevauchement de 200,
 *    dimensionné pour une machine CPU (contexte LLM court).
 */
@Service
public class ChunkingService {

    public record Section(String title, String body) {}

    private static final int CHUNK_SIZE = 1200;
    private static final int OVERLAP = 200;

    public List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        String currentTitle = "Document";
        int currentLevel = 0;
        StringBuilder currentBody = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.matches("^#{1,6}\\s+.*")) {
                if (!currentBody.toString().isBlank() || currentLevel > 1) {
                    sections.add(new Section(currentTitle, currentBody.toString().trim()));
                }
                currentLevel = line.indexOf(' ');
                currentTitle = line.replaceFirst("^#{1,6}\\s+", "").trim();
                currentBody = new StringBuilder();
            } else {
                currentBody.append(line).append('\n');
            }
        }
        sections.add(new Section(currentTitle, currentBody.toString().trim()));
        return sections;
    }

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        String t = text.trim();
        if (t.isEmpty()) return chunks;
        int start = 0;
        while (start < t.length()) {
            int end = Math.min(start + CHUNK_SIZE, t.length());
            chunks.add(t.substring(start, end));
            if (end == t.length()) break;
            start = end - OVERLAP;
        }
        return chunks;
    }
}
