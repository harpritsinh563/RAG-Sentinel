package com.ragsentinel.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class SessionTrackingFilter implements Filter {

    public static final String SESSION_ID_KEY = "X-Session-ID";
    public static final String MDC_SESSION_ID = "sessionId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest) {
            // 1. Extract session ID from headers, fallback to generating a new one
            String sessionId = httpRequest.getHeader(SESSION_ID_KEY);
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = "sess_" + UUID.randomUUID().toString().substring(0, 8);
            }

            // 2. Push to Slf4j MDC so every log line automatically contains the session context
            MDC.put(MDC_SESSION_ID, sessionId);

            // 3. Attach it to the HTTP Response so clients know their current thread ID
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(SESSION_ID_KEY, sessionId);
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_SESSION_ID);
        }
    }
}
