package com.enterprise.ai.cache;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.enterprise.ai.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Prompt -> response cache keyed by embedding similarity.
 * lookup(): embeds the prompt and runs a top-1 Qdrant search with score_threshold = similarity-threshold.
 * store():  embeds the prompt and upserts (prompt text as content, answer + timestamp as payload).
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    static final String META_RESPONSE = "response";
    static final String META_CACHED_AT = "cachedAt";

    public record CacheHit(String cachedPrompt, String response, double similarity) {
    }

    private final VectorStore vectorStore;
    private final AppProperties.SemanticCache cfg;
    private final Executor writer;

    public SemanticCacheService(@Qualifier("semanticCacheVectorStore") VectorStore vectorStore,
                                AppProperties props,
                                @Qualifier("cacheWriteExecutor") Executor writer) {
        this.vectorStore = vectorStore;
        this.cfg = props.semanticCache();
        this.writer = writer;
    }

    public Optional<CacheHit> lookup(String prompt) {
        long cutoff = Instant.now().minus(cfg.ttl()).toEpochMilli();

        SearchRequest request = SearchRequest.builder()
                .query(prompt)
                .topK(1)
                .similarityThreshold(cfg.similarityThreshold())
                .filterExpression(new FilterExpressionBuilder().gte(META_CACHED_AT, cutoff).build())
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);
        if (docs == null || docs.isEmpty()) {
            return Optional.empty();
        }
        Document best = docs.get(0);
        Object cached = best.getMetadata().get(META_RESPONSE);
        if (!(cached instanceof String answer) || answer.isBlank()) {
            return Optional.empty();
        }
        double score = best.getScore() == null ? cfg.similarityThreshold() : best.getScore();
        return Optional.of(new CacheHit(best.getText(), answer, score));
    }

    public void store(String prompt, String response) {
        // Deterministic id: re-asking the exact same (normalised) prompt overwrites instead of duplicating.
        String id = UUID.nameUUIDFromBytes(normalise(prompt).getBytes(StandardCharsets.UTF_8)).toString();
        Document doc = Document.builder()
                .id(id)
                .text(prompt)
                .metadata(Map.<String, Object>of(
                        META_RESPONSE, response,
                        META_CACHED_AT, System.currentTimeMillis()))
                .build();
        vectorStore.add(List.of(doc));
    }

    /** Fire-and-forget so the caller does not pay the embedding + upsert latency. */
    public void storeAsync(String prompt, String response) {
        CompletableFuture.runAsync(() -> {
            try {
                store(prompt, response);
            } catch (Exception e) {
                log.warn("Semantic cache write failed (ignored): {}", e.toString());
            }
        }, writer);
    }

    private static String normalise(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
