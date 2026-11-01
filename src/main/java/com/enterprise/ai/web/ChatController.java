package com.enterprise.ai.web;

import com.enterprise.ai.web.ChatDtos.ChatApiRequest;
import com.enterprise.ai.web.ChatDtos.ChatApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/chat", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatApiResponse chat(@Valid @RequestBody ChatApiRequest request) {
        return chatService.chat(request.conversationId(), request.message());
    }
}
