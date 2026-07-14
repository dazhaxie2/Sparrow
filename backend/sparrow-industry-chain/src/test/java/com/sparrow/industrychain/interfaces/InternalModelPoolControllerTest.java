package com.sparrow.industrychain.interfaces;

import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelKind;
import com.sparrow.common.ai.model.ModelScene;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.application.ModelPoolQueryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalModelPoolControllerTest {

    private static final String INTERNAL_TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void requiresConfiguredSharedTokenBeforeReturningPlainKey() {
        ModelPoolQueryService service = mock(ModelPoolQueryService.class);
        InternalModelPoolController controller = new InternalModelPoolController(service, INTERNAL_TOKEN);

        assertThatThrownBy(() -> controller.active("wrong", "sparrow_ai_chat"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("凭据无效");
        verifyNoInteractions(service);
    }

    @Test
    void returnsConfigurationForMatchingToken() {
        ModelPoolQueryService service = mock(ModelPoolQueryService.class);
        ModelConfigRecord record = new ModelConfigRecord(1L, "chat", "https://provider.example/v1",
                "chat-1", "secret", 3000, 60, 1, true,
                ModelScene.SPARROW_AI_CHAT, ModelKind.CHAT);
        when(service.activeForSparrowAi("sparrow_ai_chat")).thenReturn(record);
        InternalModelPoolController controller = new InternalModelPoolController(service, INTERNAL_TOKEN);

        assertThat(controller.active(INTERNAL_TOKEN, "sparrow_ai_chat").data()).isSameAs(record);
    }

    @Test
    void failsClosedWhenServerTokenIsMissing() {
        ModelPoolQueryService service = mock(ModelPoolQueryService.class);
        InternalModelPoolController controller = new InternalModelPoolController(service, "");

        assertThatThrownBy(() -> controller.active("anything", "sparrow_ai_chat"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未配置");
        verifyNoInteractions(service);
    }
}
