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

    public AiAgentProfile withRuntimeValues(String prompt, boolean active,
                                            int contextMessages, int contextChars,
                                            int outputChars, int steps,
                                            Long operatorId, Instant changedAt) {
        return new AiAgentProfile(service, agentKey, displayName, description,
                prompt, active, contextMessages, contextChars, outputChars, steps,
                operatorId, changedAt);
    }
}
