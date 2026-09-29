package com.ebcdc.assistant.rag;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Couche 1 de la vérification de complétude : checklist déterministe,
 * instantanée et gratuite (aucun appel LLM).
 */
@Service
public class CompletenessChecker {

    private static final List<String> REQUIRED_SECTIONS = List.of(
            "Contexte", "Périmètre", "Besoins fonctionnels",
            "Contraintes", "Critères d'acceptation", "Délais");

    private static final int MIN_SECTION_LENGTH = 200;

    private final ChunkingService chunkingService;

    public CompletenessChecker(ChunkingService chunkingService) {
        this.chunkingService = chunkingService;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT)
                .replace("’", "'").trim().replaceFirst("^\\d+[.)]?\\s+", "");
    }

    public Map<String, Object> deterministicReport(String content) {
        List<ChunkingService.Section> sections = chunkingService.splitSections(content);
        List<String> present = sections.stream().map(ChunkingService.Section::title).toList();
        List<String> presentNorm = present.stream().map(CompletenessChecker::normalize).toList();

        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_SECTIONS) {
            String rn = normalize(required);
            boolean found = presentNorm.stream().anyMatch(p -> p.equals(rn) || p.startsWith(rn + " "));
            if (!found) missing.add(required);
        }
        List<String> tooShort = sections.stream()
                .filter(s -> s.body().length() < MIN_SECTION_LENGTH)
                .map(s -> s.title() + " (" + s.body().length() + " caractères)")
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sections_presentes", present);
        report.put("sections_obligatoires_manquantes", missing);
        report.put("sections_trop_courtes", tooShort);
        report.put("seuil_longueur_section", MIN_SECTION_LENGTH);
        report.put("longueur_totale", content.length());
        return report;
    }
}
