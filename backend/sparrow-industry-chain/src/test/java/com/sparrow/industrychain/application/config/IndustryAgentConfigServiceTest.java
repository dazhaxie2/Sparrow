package com.sparrow.industrychain.application.config;

import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.infrastructure.client.UserClient;
import com.sparrow.industrychain.infrastructure.persistence.AgentConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndustryAgentConfigServiceTest {

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);
    private final UserClient users = mock(UserClient.class);
    private final IndustryAgentConfigService service = new IndustryAgentConfigService(repository, users);

    @Test
    void administratorCanUpdateRegisteredAgentAndAuditChange() {
        AiAgentProfile current = service.runtime(IndustryAgentConfigService.PLANNING_CHAT);
        when(repository.find(current.agentKey())).thenReturn(Optional.of(current));
        when(users.profile(3L)).thenReturn(ApiResponse.ok(Map.of("role", "admin")));
        when(repository.update(current.agentKey(), "新的产业链规划提示词，长度足够并且会在下一次请求生效。",
                true, 10, 7000, 40000, 4, 3L)).thenReturn(1);

        AiAgentProfile saved = service.save(3L, new IndustryAgentConfigService.SaveRequest(
                current.agentKey(), "新的产业链规划提示词，长度足够并且会在下一次请求生效。",
                true, 10, 7000, 40000, 4));

        assertThat(saved.agentKey()).isEqualTo(current.agentKey());
        verify(repository).audit(current.agentKey(), 3L,
                "更新提示词与运行参数（未记录提示词正文）");
    }

    @Test
    void ordinaryUserCannotUpdateAgent() {
        when(users.profile(4L)).thenReturn(ApiResponse.ok(Map.of("role", "user")));

        assertThatThrownBy(() -> service.save(4L, new IndustryAgentConfigService.SaveRequest(
                IndustryAgentConfigService.PLANNING_CHAT,
                "这是一个长度足够但不应被普通用户保存的系统提示词内容。",
                true, 12, 8000, 60000, 3)))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(403);
    }
}
