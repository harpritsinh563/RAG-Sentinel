package com.ragsentinel.controller;

import com.ragsentinel.filter.SessionTrackingFilter;
import com.ragsentinel.service.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import static com.ragsentinel.constants.EndpointConstants.CHAT;

@RestController
@RequestMapping(CHAT)
public class ChatController {
    private final ChatService chatService;
    private static Logger log = LoggerFactory.getLogger(ChatController.class);

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateResponse(
            @RequestParam(name = "prompt") String prompt,
            @RequestHeader(name = "X-Session-ID", required = false) String sessionId) {

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = org.slf4j.MDC.get(SessionTrackingFilter.MDC_SESSION_ID);
        }

        log.info("Received streaming request for session [{}]: {}", sessionId, prompt);
        return chatService.streamChatWithContext(prompt, sessionId);
    }
}
