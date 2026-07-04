package com.ragsentinel.service.chat;

import reactor.core.publisher.Flux;

public interface ChatService {
    String chatWithContext(String prompt, String sessionId);
    Flux<String> streamChatWithContext(String prompt, String sessionId);
}
