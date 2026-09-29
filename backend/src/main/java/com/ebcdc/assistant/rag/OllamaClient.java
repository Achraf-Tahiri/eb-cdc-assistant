package com.ebcdc.assistant.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client HTTP minimal vers l'API Ollama locale.
 * - /api/embed  : embeddings par lot (nomic-embed-text, 768 dimensions)
 * - /api/generate : génération JSON (qwen3:4b, thinking désactivé, température basse)
 *
 * Timeout de lecture volontairement long : sur CPU sans GPU, une analyse
 * peut prendre plusieurs minutes.
 */
@Component
public class OllamaClient {

    private final RestClient client;
    private final String generationModel;
    private final String embeddingModel;

    public OllamaClient(@Value("${app.ollama.base-url}") String baseUrl,
                        @Value("${app.ollama.generation-model}") String generationModel,
                        @Value("${app.ollama.embedding-model}") String embeddingModel,
                        @Value("${app.ollama.read-timeout-seconds:600}") long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.generationModel = generationModel;
        this.embeddingModel = embeddingModel;
    }

    @SuppressWarnings("unchecked")
    public List<List<Double>> embed(List<String> texts) {
        Map<String, Object> body = Map.of("model", embeddingModel, "input", texts);
        Map<String, Object> resp = client.post().uri("/api/embed")
                .body(body).retrieve().body(Map.class);
        if (resp == null || resp.get("embeddings") == null) {
            throw new IllegalStateException("Réponse embeddings vide d'Ollama");
        }
        return (List<List<Double>>) resp.get("embeddings");
    }

    public List<Double> embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    @SuppressWarnings("unchecked")
    public String generateJson(String prompt) {
        Map<String, Object> body = Map.of(
                "model", generationModel,
                "prompt", prompt,
                "stream", false,
                "format", "json",
                "think", false,
                "options", Map.of(
                        "temperature", 0.1,
                        "seed", 42,
                        "num_ctx", 8192));
        Map<String, Object> resp = client.post().uri("/api/generate")
                .body(body).retrieve().body(Map.class);
        if (resp == null || resp.get("response") == null) {
            throw new IllegalStateException("Réponse de génération vide d'Ollama");
        }
        return (String) resp.get("response");
    }
}
