package com.enterprise.ai.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal pass-through endpoint: prompt in, LLM answer out.
 * Uses a plain ChatClient (no memory, semantic cache, guardrails or tools),
 * unlike /api/v1/chat which runs the full advisor pipeline.
 */
@RestController
@RequestMapping(path = "/api/v1/prompt", produces = MediaType.APPLICATION_JSON_VALUE)
public class PromptController {

    public record PromptRequest(@NotBlank @Size(max = 8000) String prompt) {
    }

    public record PromptResponse(String response) {
    }

    private final ChatClient llm;

    public PromptController(ChatClient.Builder builder) {
        // ChatClient.Builder is prototype-scoped, so this client is independent of the advisor-wired one.
        this.llm = builder.build();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public PromptResponse prompt(@Valid @RequestBody PromptRequest request) {
        String answer = llm.prompt()
                .user(request.prompt())
                .call()
                .content();
        return new PromptResponse(answer);
    }
}
