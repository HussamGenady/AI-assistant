package com.enterprise.ai.web;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import com.enterprise.ai.advisor.AdvisorContextKeys;
import com.enterprise.ai.cache.CacheStatus;
import com.enterprise.ai.web.ChatDtos.ChatApiResponse;
import com.enterprise.ai.web.ChatDtos.Metrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final ObjectProvider<ToolCallbackProvider> mcpToolProviders;

    public ChatService(ChatClient chatClient, PromptTemplate systemPromptTemplate,
                       ObjectProvider<ToolCallbackProvider> mcpToolProviders) {
        this.chatClient = chatClient;
        this.systemPromptTemplate = systemPromptTemplate;
        this.mcpToolProviders = mcpToolProviders;
    }

    public ChatApiResponse chat(String conversationId, String message) {
        String systemText = systemPromptTemplate.render(Map.of(
                "current_date", LocalDate.now().toString(),
                "assistant_name", "Enterprise Assistant"));

        // Resolved per request so tools from MCP servers are picked up once the clients are connected
        ToolCallback[] mcpTools = mcpToolProviders.orderedStream()
                .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                .toArray(ToolCallback[]::new);

        ChatClientResponse result = chatClient.prompt()
                .system(systemText)
                .toolCallbacks(mcpTools)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatClientResponse();

        String answer = Optional.ofNullable(result.chatResponse())
                .map(ChatResponse::getResult)
                .map(g -> g.getOutput().getText())
                .orElse("");

        Map<String, Object> ctx = result.context();
        CacheStatus status = ctx.get(AdvisorContextKeys.CACHE_STATUS) instanceof CacheStatus s ? s : CacheStatus.BYPASS;

        Metrics metrics = new Metrics(
                status.name(),
                status == CacheStatus.HIT,
                ctx.get(AdvisorContextKeys.CACHE_SIMILARITY) instanceof Double d ? d : null,
                number(ctx, AdvisorContextKeys.DURATION_MS),
                (int) number(ctx, AdvisorContextKeys.PROMPT_TOKENS),
                (int) number(ctx, AdvisorContextKeys.COMPLETION_TOKENS),
                (int) number(ctx, AdvisorContextKeys.TOTAL_TOKENS),
                String.valueOf(ctx.getOrDefault(AdvisorContextKeys.MODEL, "unknown")));

        return new ChatApiResponse(conversationId, answer, metrics);
    }

    private static long number(Map<String, Object> ctx, String key) {
        return ctx.get(key) instanceof Number n ? n.longValue() : 0L;
    }
}
