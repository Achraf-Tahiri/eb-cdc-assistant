package com.ebcdc.assistant.rag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CompletenessCheckerTest {
    final ChunkingService chunker = new ChunkingService();
    final CompletenessChecker checker = new CompletenessChecker(chunker);
    @Test void emptyTemplateHasPresentButShortSections() {
        var report=checker.deterministicReport("# Exemple\n\n## Contexte\n\n## Périmètre\n\n## Besoins fonctionnels\n\n## Contraintes\n\n## Critères d'acceptation\n\n## Délais\n");
        assertThat((List<?>) report.get("sections_obligatoires_manquantes")).isEmpty();
        assertThat((List<?>) report.get("sections_trop_courtes")).hasSize(6);
    }
    @Test void partialHeadingDoesNotSatisfyRequiredSection() {
        var report=checker.deterministicReport("## C\nTexte");
        assertThat((List<?>)report.get("sections_obligatoires_manquantes")).hasSize(6);
    }
    @Test void accentsCaseAndNumberingAreTolerated() {
        var report=checker.deterministicReport("## 1. PERIMETRE\n"+"a".repeat(200));
        assertThat((List<String>)report.get("sections_obligatoires_manquantes")).doesNotContain("Périmètre");
        assertThat((List<?>)report.get("sections_trop_courtes")).isEmpty();
    }
    @Test void chunksPreserveContentAndOverlap() {
        var input="0123456789".repeat(350);
        var chunks=chunker.chunk(input);
        assertThat(chunks).hasSize(4).allMatch(c -> c.length() <= 1200);
        String reconstructed=chunks.getFirst();
        for(int i=1;i<chunks.size();i++) {
            assertThat(chunks.get(i).substring(0,200)).isEqualTo(chunks.get(i-1).substring(chunks.get(i-1).length()-200));
            reconstructed+=chunks.get(i).substring(200);
        }
        assertThat(reconstructed).isEqualTo(input);
        assertThat(chunker.chunk("  ")).isEmpty();
    }
}
