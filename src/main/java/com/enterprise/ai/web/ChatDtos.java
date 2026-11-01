package com.enterprise.ai.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ChatDtos {

    public record ChatApiRequest(
            @NotBlank @Size(max = 128) String conversationId,
            @NotBlank @Size(max = 8000) String message) {
    }

    public record Metrics(
            String cacheStatus,
            boolean cacheHit,
            Double cacheSimilarity,
            long durationMs,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String model) {
    }

    public record ChatApiResponse(String conversationId, String response, Metrics metrics) {
    }

    private ChatDtos() {
    }
}
