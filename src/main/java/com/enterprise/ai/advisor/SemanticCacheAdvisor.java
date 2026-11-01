package com.enterprise.ai.advisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.enterprise.ai.cache.CacheStatus;
import com.enterprise.ai.cache.SemanticCacheService;
import com.enterprise.ai.cache.SemanticCacheService.CacheHit;
import com.enterprise.ai.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Sits closest to the model, AFTER the memory advisor. On a HIT it short-circuits the chain, and the
 * synthetic response still flows back through the memory advisor, so the turn is persisted to history.
 * On a MISS it tags the response so TelemetryPostAdvisor can index the new prompt/response pair.
 * Cache failures never fail the request (fail-open).
 */
@Component
public class SemanticCacheAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheAdvisor.class);

    private final SemanticCacheService cache;
    private final AppProperties.SemanticCache cfg;

    public SemanticCacheAdvisor(SemanticCacheService cache, AppProperties props) {
        this.cache = cache;
        this.cfg = props.semanticCache();
    }

    @Override
    public String getName() {
        return "SemanticCacheAdvisor";
    }

    @Override
    public int getOrder() {
        // Default chat-memory advisor order is HIGHEST_PRECEDENCE + 1000, so this runs after it.
        return Ordered.HIGHEST_PRECEDENCE + 2000;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String prompt = request.prompt().getUserMessage().getText();
        boolean followUp = request.prompt().getInstructions().stream().anyMatch(AssistantMessage.class::isInstance);

        if (!cfg.enabled() || !StringUtils.hasText(prompt) || (followUp && !cfg.cacheFollowUps())) {
            return tag(chain.nextCall(request), Map.of(AdvisorContextKeys.CACHE_STATUS, CacheStatus.BYPASS));
        }

        Optional<CacheHit> hit = safeLookup(prompt);
        if (hit.isPresent()) {
            CacheHit h = hit.get();
            log.info("[cache] HIT similarity={}", String.format("%.4f", h.similarity()));
            ChatResponse cached = new ChatResponse(List.of(new Generation(new AssistantMessage(h.response()))));
            Map<String, Object> ctx = new HashMap<>(request.context());
            ctx.put(AdvisorContextKeys.CACHE_STATUS, CacheStatus.HIT);
            ctx.put(AdvisorContextKeys.CACHE_SIMILARITY, h.similarity());
            return new ChatClientResponse(cached, ctx);
        }

        log.info("[cache] MISS");
        return tag(chain.nextCall(request), Map.of(
                AdvisorContextKeys.CACHE_STATUS, CacheStatus.MISS,
                AdvisorContextKeys.CACHE_PROMPT, prompt));
    }

    private Optional<CacheHit> safeLookup(String prompt) {
        try {
            return cache.lookup(prompt);
        } catch (Exception e) {
            log.warn("Semantic cache lookup failed, continuing without cache: {}", e.toString());
            return Optional.empty();
        }
    }

    private static ChatClientResponse tag(ChatClientResponse response, Map<String, Object> extra) {
        Map<String, Object> ctx = new HashMap<>(response.context());
        ctx.putAll(extra);
        return new ChatClientResponse(response.chatResponse(), ctx);
    }
}
