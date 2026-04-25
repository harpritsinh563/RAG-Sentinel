package com.ragsentinel.controller;

import com.ragsentinel.service.chat.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ragsentinel.constants.EndpointConstants.CHAT;

@RestController
@RequestMapping(CHAT)
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<String> generateResponse(@RequestParam(name = "prompt") String prompt) {
        return ResponseEntity.ok(chatService.chatWithContext(prompt));
    }
}
