package com.enterprise.ai.advisor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.enterprise.ai.cache.CacheStatus;
import com.enterprise.ai.cache.SemanticCacheService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Response-side advisor (outer ring, so it times the whole chain including memory, cache and tool calls):
 *  - records duration + token usage (Micrometer + response context for the REST layer)
 *  - on a cache MISS, indexes the prompt/response pair in the semantic cache (async)
 */
@Component
public class TelemetryPostAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPostAdvisor.class);

    private final SemanticCacheService cache;
    private final MeterRegistry registry;

    public TelemetryPostAdvisor(SemanticCacheService cache, MeterRegistry registry) {
        this.cache = cache;
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "TelemetryPostAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException e) {
            registry.counter("ai.chat.errors", "type", e.getClass().getSimpleName()).increment();
            throw e;
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        Map<String, Object> ctx = new HashMap<>(response.context());
        CacheStatus status = ctx.get(AdvisorContextKeys.CACHE_STATUS) instanceof CacheStatus s ? s : CacheStatus.BYPASS;

        ChatResponse chat = response.chatResponse();
        int promptTokens = 0, completionTokens = 0, totalTokens = 0;
        String model = status == CacheStatus.HIT ? "semantic-cache" : "unknown";
        String answer = null;

        if (chat != null) {
            Usage usage = chat.getMetadata() == null ? null : chat.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                totalTokens = usage.getTotalTokens() == null ? promptTokens + completionTokens : usage.getTotalTokens();
            }
            if (status != CacheStatus.HIT && chat.getMetadata() != null && StringUtils.hasText(chat.getMetadata().getModel())) {
                model = chat.getMetadata().getModel();
            }
            if (chat.getResult() != null && chat.getResult().getOutput() != null) {
                answer = chat.getResult().getOutput().getText();
            }
        }

        // Semantic cache write-back (only for MISS; HIT is already cached, BYPASS is intentionally not cached)
        if (status == CacheStatus.MISS && StringUtils.hasText(answer)
                && ctx.get(AdvisorContextKeys.CACHE_PROMPT) instanceof String cachedPrompt) {
            cache.storeAsync(cachedPrompt, answer);
        }

        Timer.builder("ai.chat.duration").tag("cache", status.name()).register(registry)
                .record(Duration.ofMillis(durationMs));
        registry.counter("ai.chat.tokens", "type", "prompt").increment(promptTokens);
        registry.counter("ai.chat.tokens", "type", "completion").increment(completionTokens);
        registry.counter("ai.chat.cache", "status", status.name()).increment();

        log.info("[post] cache={} durationMs={} tokens(prompt/completion/total)={}/{}/{} model={}",
                status, durationMs, promptTokens, completionTokens, totalTokens, model);

        ctx.put(AdvisorContextKeys.DURATION_MS, durationMs);
        ctx.put(AdvisorContextKeys.PROMPT_TOKENS, promptTokens);
        ctx.put(AdvisorContextKeys.COMPLETION_TOKENS, completionTokens);
        ctx.put(AdvisorContextKeys.TOTAL_TOKENS, totalTokens);
        ctx.put(AdvisorContextKeys.MODEL, model);
        return new ChatClientResponse(chat, ctx);
    }
}
