package com.sparrow.common.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiHarnessTest {

    @Test
    void completedRunCarriesBoundedLifecycleMetadata() {
        AiHarness.Run run = AiHarness.start("graph-dialog")
                .checkpoint(AiHarness.Stage.POLICY, "输入策略通过")
                .contextMessages(8)
                .checkpoint(AiHarness.Stage.CONTEXT, "装配最近 8 条会话消息")
                .checkpoint(AiHarness.Stage.VALIDATION, "输出校验通过");

        AiHarness.Metadata metadata = run.complete();

        assertEquals(1, metadata.schemaVersion());
        assertEquals("graph-dialog", metadata.surface());
        assertEquals("completed", metadata.status());
        assertEquals("completed", metadata.currentStage());
        assertEquals(8, metadata.contextMessages());
        assertFalse(metadata.fallbackUsed());
        assertFalse(metadata.traceId().isBlank());
        assertEquals("received", metadata.events().get(0).stage());
    }

    @Test
    void fallbackProducesDegradedTerminalStateWithoutLeakingRawFailure() {
        AiHarness.Run run = AiHarness.start("INVALID USER SURFACE")
                .fallback("上游模型不可用，已切换本地规则")
                .warning("回答来自降级路径");

        AiHarness.Metadata metadata = run.complete();

        assertEquals("general-chat", metadata.surface());
        assertEquals("degraded", metadata.status());
        assertTrue(metadata.fallbackUsed());
        assertEquals(1, metadata.warnings().size());
        assertThrows(IllegalStateException.class,
                () -> run.checkpoint(AiHarness.Stage.CONTEXT, "不允许终态后继续"));
    }

    @Test
    void failedRunRecordsRetryability() {
        AiHarness.Metadata metadata = AiHarness.start("industry-chain-planning")
                .fail(true, "模型提供方暂时不可用");

        assertEquals("failed", metadata.status());
        assertEquals("failed", metadata.currentStage());
        assertTrue(metadata.retryable());
    }

    @Test
    void providerFailureTextIsSafeForLogs() {
        String safe = AiHarness.safeFailure(new RuntimeException(
                "Authorization: Bearer secret-value API_KEY=sk-1234567890 password=hunter2"));

        assertFalse(safe.contains("secret-value"));
        assertFalse(safe.contains("1234567890"));
        assertFalse(safe.contains("hunter2"));
        assertTrue(safe.contains("***"));
    }
}
