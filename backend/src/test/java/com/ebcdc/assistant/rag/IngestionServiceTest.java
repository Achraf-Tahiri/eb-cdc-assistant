package com.ebcdc.assistant.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    @Test
    void reimportingSameLabelReplacesOldChunksBeforeInsertion() {
        var ollama = mock(OllamaClient.class);
        var vectors = mock(RagVectorStore.class);
        var service = new IngestionService(new ChunkingService(), ollama, vectors);
        when(ollama.embed(anyList())).thenReturn(List.of(List.of(0.1, 0.2)));

        int inserted = service.ingestText("  Référence factures  ",
                "## Contraintes\nLe fichier exporté contient une ligne d'en-tête.");

        assertThat(inserted).isEqualTo(1);
        var order = inOrder(vectors);
        order.verify(vectors).deleteBySourceLabel("Référence factures");
        order.verify(vectors).insertChunk(null, "Référence factures", "Contraintes",
                "Le fichier exporté contient une ligne d'en-tête.", List.of(0.1, 0.2));
    }
}
