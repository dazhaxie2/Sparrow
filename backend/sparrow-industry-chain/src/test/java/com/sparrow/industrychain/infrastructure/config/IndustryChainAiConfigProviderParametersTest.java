package com.sparrow.industrychain.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndustryChainAiConfigProviderParametersTest {

    @Test
    void disablesDefaultThinkingForBigModelGlm() {
        Map<String, Object> parameters = IndustryChainAiConfig.providerCustomParameters(
                "https://open.bigmodel.cn/api/paas/v4", "glm-4.7");

        assertThat(parameters).containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void doesNotSendProviderSpecificParametersToOtherEndpoints() {
        assertThat(IndustryChainAiConfig.providerCustomParameters(
                "https://api.openai.com/v1", "gpt-4o-mini")).isEmpty();
    }
}
