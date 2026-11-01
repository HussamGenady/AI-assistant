package com.enterprise.ai.cache;

public enum CacheStatus {
    /** Served from the semantic cache; the LLM was not called. */
    HIT,
    /** Cache consulted, nothing close enough; LLM was called and the answer will be indexed. */
    MISS,
    /** Cache not consulted (disabled, or a follow-up turn that depends on history). */
    BYPASS
}
