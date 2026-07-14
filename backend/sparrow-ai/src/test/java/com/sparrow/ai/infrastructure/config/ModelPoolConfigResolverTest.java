package com.sparrow.ai.infrastructure.config;

import com.sparrow.ai.infrastructure.client.ChainModelConfigClient;
import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelKind;
import com.sparrow.common.ai.model.ModelScene;
import com.sparrow.common.api.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelPoolConfigResolverTest {

    private static final String INTERNAL_TOKEN = "0123456789abcdef0123456789abcdef";

    private ChainModelConfigClient client;
    private ModelPoolConfigResolver resolver;

    @BeforeEach
    void setUp() {
        client = mock(ChainModelConfigClient.class);
        resolver = new ModelPoolConfigResolver(client,
                new AiProperties("https://fallback.example/v1", "fallback-key",
                        "fallback-chat", "fallback-embedding", 3), INTERNAL_TOKEN);
    }

    @Test
    void prefersValidRemoteSceneConfiguration() {
        ModelConfigRecord remote = config(9L, ModelScene.SPARROW_AI_CHAT, ModelKind.CHAT,
                "remote-chat", 4096);
        when(client.active(INTERNAL_TOKEN, "sparrow_ai_chat")).thenReturn(ApiResponse.ok(remote));

        ModelConfigRecord result = resolver.resolve(ModelScene.SPARROW_AI_CHAT);

        assertThat(result).isSameAs(remote);
        assertThat(result.apiKey()).isEqualTo("remote-key");
    }

    @Test
    void fallsBackToEnvironmentWhenOwnerIsUnavailable() {
        when(client.active(INTERNAL_TOKEN, "sparrow_ai_embedding")).thenThrow(new IllegalStateException("down"));

        ModelConfigRecord result = resolver.resolve(ModelScene.SPARROW_AI_EMBEDDING);

        assertThat(result.modelName()).isEqualTo("fallback-embedding");
        assertThat(result.kind()).isEqualTo(ModelKind.EMBEDDING);
        assertThat(result.maxTokens()).isZero();
    }

    @Test
    void rejectsMismatchedRemoteKindAndUsesValidatedFallback() {
        ModelConfigRecord mismatched = config(10L, ModelScene.SPARROW_AI_CHAT, ModelKind.EMBEDDING,
                "wrong", 0);
        when(client.active(INTERNAL_TOKEN, "sparrow_ai_chat")).thenReturn(ApiResponse.ok(mismatched));

        ModelConfigRecord result = resolver.resolve(ModelScene.SPARROW_AI_CHAT);

        assertThat(result.modelName()).isEqualTo("fallback-chat");
        assertThat(result.kind()).isEqualTo(ModelKind.CHAT);
    }

    @Test
    void missingInternalTokenFailsClosedAndUsesEnvironmentFallback() {
        resolver = new ModelPoolConfigResolver(client,
                new AiProperties("https://fallback.example/v1", "fallback-key",
                        "fallback-chat", "fallback-embedding", 3), "");

        ModelConfigRecord result = resolver.resolve(ModelScene.SPARROW_AI_CHAT);

        assertThat(result.modelName()).isEqualTo("fallback-chat");
        verifyNoInteractions(client);
    }

    private static ModelConfigRecord config(Long id, ModelScene scene, ModelKind kind,
                                            String modelName, int maxTokens) {
        return new ModelConfigRecord(id, "remote", "https://remote.example/v1", modelName,
                "remote-key", maxTokens, 60, 1, true, scene, kind);
    }
}
