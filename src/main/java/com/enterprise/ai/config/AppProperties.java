package com.enterprise.ai.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue SemanticCache semanticCache,
        @DefaultValue Memory memory,
        @DefaultValue Tavily tavily,
        @DefaultValue Guardrails guardrails) {

    public record SemanticCache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("semantic_cache") String collection,
            @DefaultValue("0.92") double similarityThreshold,
            @DefaultValue("PT6H") Duration ttl,
            @DefaultValue("false") boolean cacheFollowUps) {
    }

    public record Memory(
            @DefaultValue("chat_memory") String collection,
            @DefaultValue("20") int maxMessages) {
    }

    public record Tavily(
            @DefaultValue("") String apiKey,
            @DefaultValue("https://api.tavily.com") String baseUrl,
            @DefaultValue("basic") String searchDepth,
            @DefaultValue("5") int maxResults,
            @DefaultValue("800") int maxContentChars,
            @DefaultValue("3s") Duration connectTimeout,
            @DefaultValue("15s") Duration readTimeout) {
    }

    public record Guardrails(
            @DefaultValue("8000") int maxPromptChars,
            @DefaultValue List<String> blockedPatterns) {
    }
}
