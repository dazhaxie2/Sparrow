package com.sparrow.common.ai;

import java.time.Instant;

/**
 * Sanitized, service-owned runtime configuration for a user-facing Agent.
 * API keys and provider credentials intentionally do not belong in this contract.
 */
public record AiAgentProfile(
        String service,
        String agentKey,
        String displayName,
        String description,
        String systemPrompt,
        boolean enabled,
        int maxContextMessages,
        int maxContextChars,
        int maxOutputChars,
        int maxSteps,
        Long updatedBy,
        Instant updatedAt) {
}
