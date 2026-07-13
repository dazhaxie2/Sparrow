package com.sparrow.industrychain.application.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchRunnerTest {

    @Test
    void mapsProviderBalanceErrorToActionableMessage() {
        RuntimeException error = new RuntimeException(
                "{\"error\":{\"code\":\"1113\",\"message\":\"余额不足或无可用资源包,请充值。\"}}");

        assertThat(ResearchRunner.userFacingFailure(error))
                .isEqualTo("AI 模型账户余额不足或无可用资源包，请充值或切换可用模型配置后重试。");
    }

    @Test
    void mapsNestedTimeoutWithoutLeakingProviderDetails() {
        RuntimeException error = new RuntimeException("provider failed",
                new IllegalStateException("request timed out after 60 seconds"));

        assertThat(ResearchRunner.userFacingFailure(error))
                .isEqualTo("AI 服务响应超时，调研任务已安全停止。");
    }
}
