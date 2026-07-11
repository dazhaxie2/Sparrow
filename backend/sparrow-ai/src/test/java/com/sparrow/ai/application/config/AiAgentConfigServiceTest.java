package com.sparrow.ai.application.config;

import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.ai.infrastructure.persistence.AiAgentConfigRepository;
import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentConfigServiceTest {

    private final AiAgentConfigRepository repository = mock(AiAgentConfigRepository.class);
    private final UserClient users = mock(UserClient.class);
    private final AiAgentConfigService service = new AiAgentConfigService(repository, users);

    @Test
    void adminCanUpdateReviewedRuntimeFieldsAndAuditWithoutPromptBody() {
        AiAgentProfile current = AiAgentConfigService.defaultProfile();
        when(users.profile(7L)).thenReturn(ApiResponse.ok(Map.of("role", "admin")));
        when(repository.find(current.agentKey())).thenReturn(Optional.of(current));
        when(repository.update(current.agentKey(), "新的系统提示词，长度足够并用于验证管理员配置。", true,
                10, 5000, 30000, 4, 7L)).thenReturn(1);

        AiAgentProfile saved = service.save(7L, new AiAgentConfigService.SaveRequest(
                current.agentKey(), "新的系统提示词，长度足够并用于验证管理员配置。",
                true, 10, 5000, 30000, 4));

        assertThat(saved.agentKey()).isEqualTo(current.agentKey());
        verify(repository).audit(current.agentKey(), 7L,
                "更新提示词与运行参数（未记录提示词正文）");
    }

    @Test
    void nonAdminCannotReadConfigurations() {
        when(users.profile(8L)).thenReturn(ApiResponse.ok(Map.of("role", "user")));

        assertThatThrownBy(() -> service.list(8L))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(403);
    }
}
