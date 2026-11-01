package com.enterprise.ai.advisor;

import java.util.List;
import java.util.regex.Pattern;

import com.enterprise.ai.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Outermost request-side advisor: enforces guardrails (length + blocked patterns)
 * and logs a one-line summary of the outbound prompt. Prompt text is only logged at DEBUG.
 */
@Component
public class LoggingPreAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggingPreAdvisor.class);

    private final int maxPromptChars;
    private final List<Pattern> blocked;

    public LoggingPreAdvisor(AppProperties props) {
        this.maxPromptChars = props.guardrails().maxPromptChars();
        this.blocked = props.guardrails().blockedPatterns().stream().map(Pattern::compile).toList();
    }

    @Override
    public String getName() {
        return "LoggingPreAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String conversationId = String.valueOf(
                request.context().getOrDefault(ChatMemory.CONVERSATION_ID, ChatMemory.CONVERSATION_ID));
        String text = request.prompt().getUserMessage().getText();

        enforceGuardrails(conversationId, text);

        int chars = StringUtils.hasText(text) ? text.length() : 0;
        log.info("[pre] conversation={} messages={} userChars={} estPromptTokens~{}",
                conversationId, request.prompt().getInstructions().size(), chars, chars / 4);
        if (log.isDebugEnabled()) {
            log.debug("[pre] conversation={} prompt={}", conversationId, text);
        }
        return chain.nextCall(request);
    }

    private void enforceGuardrails(String conversationId, String text) {
        if (!StringUtils.hasText(text)) {
            throw new GuardrailViolationException("Empty prompt");
        }
        if (text.length() > maxPromptChars) {
            log.warn("[guardrail] conversation={} rejected: length {} > {}", conversationId, text.length(), maxPromptChars);
            throw new GuardrailViolationException("Prompt exceeds " + maxPromptChars + " characters");
        }
        for (Pattern p : blocked) {
            if (p.matcher(text).find()) {
                log.warn("[guardrail] conversation={} rejected: matched {}", conversationId, p.pattern());
                throw new GuardrailViolationException("Prompt rejected by security guardrail");
            }
        }
    }
}
