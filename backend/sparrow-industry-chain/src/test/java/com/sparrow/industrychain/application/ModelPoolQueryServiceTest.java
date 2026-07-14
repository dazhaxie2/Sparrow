package com.sparrow.industrychain.application;

import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelKind;
import com.sparrow.common.ai.model.ModelScene;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelPoolQueryServiceTest {

    private ModelConfigRepository repository;
    private ModelPoolQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        service = new ModelPoolQueryService(repository);
    }

    @Test
    void returnsOnlyActiveSparrowAiSceneWithPlainKey() {
        ModelConfig config = ModelConfig.decrypted(7L, "chat", "https://provider.example/v1",
                "chat-1", "secret", 3000, 60, 1, true,
                ModelScene.SPARROW_AI_CHAT, ModelKind.CHAT);
        when(repository.findActiveDecrypted(ModelScene.SPARROW_AI_CHAT)).thenReturn(Optional.of(config));

        ModelConfigRecord result = service.activeForSparrowAi("sparrow_ai_chat");

        assertThat(result.apiKey()).isEqualTo("secret");
        assertThat(result.scene()).isEqualTo(ModelScene.SPARROW_AI_CHAT);
        assertThat(result.toString()).doesNotContain("secret");
    }

    @Test
    void rejectsIndustryOwnedAndUnknownScenes() {
        assertThatThrownBy(() -> service.activeForSparrowAi("chain_report"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持");
        assertThatThrownBy(() -> service.activeForSparrowAi("unknown"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持");
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMissingActiveConfiguration() {
        when(repository.findActiveDecrypted(ModelScene.SPARROW_AI_EMBEDDING)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activeForSparrowAi("sparrow_ai_embedding"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂无激活");
    }
}
