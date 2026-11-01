package com.enterprise.ai.advisor;

/** Keys used to pass data between advisors and back to the controller via ChatClientResponse.context(). */
public final class AdvisorContextKeys {

    public static final String CACHE_STATUS = "semantic.cache.status";
    public static final String CACHE_SIMILARITY = "semantic.cache.similarity";
    public static final String CACHE_PROMPT = "semantic.cache.prompt";

    public static final String DURATION_MS = "telemetry.durationMs";
    public static final String PROMPT_TOKENS = "telemetry.promptTokens";
    public static final String COMPLETION_TOKENS = "telemetry.completionTokens";
    public static final String TOTAL_TOKENS = "telemetry.totalTokens";
    public static final String MODEL = "telemetry.model";

    private AdvisorContextKeys() {
    }
}
