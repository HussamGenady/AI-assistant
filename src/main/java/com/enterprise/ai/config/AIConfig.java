package com.enterprise.ai.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.enterprise.ai.advisor.LoggingPreAdvisor;
import com.enterprise.ai.advisor.SemanticCacheAdvisor;
import com.enterprise.ai.advisor.TelemetryPostAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Assembles the ChatClient.
 *
 * Advisor order (lowest value = outermost on the request path, outermost on the way back too):
 *   LoggingPreAdvisor        HIGHEST+10    guardrails + logging
 *   TelemetryPostAdvisor     HIGHEST+20    timing, tokens, cache write-back
 *   MessageChatMemoryAdvisor HIGHEST+1000  loads/saves history (Qdrant-backed)
 *   SemanticCacheAdvisor     HIGHEST+2000  cache lookup, short-circuits before the LLM
 *   -> model (OpenAI) with Tavily + MCP tools
 */
@Configuration
public class AIConfig {

    private static final Logger log = LoggerFactory.getLogger(AIConfig.class);

    /**
     * System prompt template (prompts/system.st). Variables are rendered per request in ChatService,
     * so values such as {current_date} are always fresh.
     */
    @Bean
    public PromptTemplate systemPromptTemplate(@Value("classpath:/prompts/system.st") Resource resource) {
        return new PromptTemplate(resource);
    }

    /** Virtual-thread executor for non-blocking semantic-cache write-back. */
    @Bean(name = "cacheWriteExecutor", destroyMethod = "shutdown")
    public ExecutorService cacheWriteExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Sliding-window memory persisted through QdrantChatMemoryRepository, keyed by conversationId. */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, AppProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.memory().maxMessages())
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory chatMemory,
                                 LoggingPreAdvisor loggingPreAdvisor,
                                 TelemetryPostAdvisor telemetryPostAdvisor,
                                 SemanticCacheAdvisor semanticCacheAdvisor) {

        return builder
                .defaultAdvisors(
                        loggingPreAdvisor,
                        telemetryPostAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        semanticCacheAdvisor)
                .defaultToolNames("tavilySearch")     // local @Bean Function<TavilySearchRequest, TavilySearchResponse>
                // MCP tools are attached per request in ChatService (resolved after MCP clients are up)
                .build();
    }
}
