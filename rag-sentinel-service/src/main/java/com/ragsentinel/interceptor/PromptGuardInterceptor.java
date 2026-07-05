package com.ragsentinel.interceptor;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

import static com.ragsentinel.constants.AICustomMetrics.GUARDRAIL_VIOLATION;
import static com.ragsentinel.constants.GeneralConstants.TYPE;

/**
 * This is a simulation class or a mock implementation of how a prompt interception
 * would work for detecting malicious prompts and fail fast
 * Purpose here is to publish metrics for guardrail violation count
 */
@Component
public class PromptGuardInterceptor implements HandlerInterceptor {
    private final MeterRegistry meterRegistry;
    // Simple blocklist for the PoC
    private final List<String> blockedKeywords = List.of(
            "ignore previous instructions",
            "ignore all previous instructions",
            "system override",
            "bypass",
            "system prompt"
    );
    public PromptGuardInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Pre-register the counter with the exact tag combination
        // so it initializes to 0 in Prometheus on startup instead of "No data"
        this.meterRegistry.counter(GUARDRAIL_VIOLATION, TYPE, "prompt_injection").increment(0);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Ideally prompt will be coming in Request body, but for POC readign it from query param
        String prompt = request.getParameter("prompt");

        if (prompt != null) {
            boolean isMalicious = blockedKeywords.stream().anyMatch(prompt.toLowerCase()::contains);

            if (isMalicious) {
                // Emitting the custom observability metric!
                meterRegistry.counter(GUARDRAIL_VIOLATION, TYPE, "prompt_injection").increment();

                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("Guardrail triggered: Malicious prompt detected.");
                return false; // Block the request
            }
        }
        return true;
    }
}
