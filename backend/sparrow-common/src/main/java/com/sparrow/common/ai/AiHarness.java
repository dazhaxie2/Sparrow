package com.sparrow.common.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * AI 对话运行时 Harness 的稳定协议与轻量状态机。
 *
 * <p>业务服务仍各自拥有上下文、工具和模型执行；本类只统一可观测生命周期，
 * 不依赖任何模型 SDK，也不允许把 prompt、密钥或异常堆栈写入对外元数据。</p>
 */
public final class AiHarness {

    public static final int SCHEMA_VERSION = 1;

    private AiHarness() {
    }

    /** 所有 AI 对话服务使用同一组生命周期阶段。 */
    public enum Stage {
        RECEIVED,
        POLICY,
        CONTEXT,
        RETRIEVAL,
        EXECUTION,
        RECOVERY,
        VALIDATION,
        PERSISTENCE,
        COMPLETED,
        FAILED
    }

    /** 单个阶段的脱敏观测事件。 */
    public record Event(String stage, String status, String detail, Instant occurredAt) {
    }

    /** 返回给客户端和日志关联使用的 Harness 摘要。 */
    public record Metadata(int schemaVersion, String traceId, String surface, String status,
                           String currentStage, boolean retryable, boolean fallbackUsed,
                           int contextMessages, List<Event> events, List<String> warnings) {
    }

    /** 启动一次独立的 AI 对话运行。 */
    public static Run start(String surface) {
        return new Run(normalizeSurface(surface), UUID.randomUUID().toString());
    }

    /** 将提供商异常压缩为可记录但不含常见凭证形态的诊断文本。 */
    public static String safeFailure(Throwable error) {
        String value = error == null ? "unknown" :
                (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        value = value
                .replaceAll("(?i)bearer\\s+[a-z0-9._~+/-]+", "Bearer ***")
                .replaceAll("(?i)sk-[a-z0-9_-]{8,}", "sk-***")
                .replaceAll("(?i)(authorization|api[ _-]?key|access[ _-]?token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=***")
                .replaceAll("\\s+", " ")
                .trim();
        return value.length() <= 300 ? value : value.substring(0, 297) + "...";
    }

    /** 只允许低基数、可检索的 surface，避免把用户输入变成日志标签。 */
    private static String normalizeSurface(String surface) {
        String value = surface == null ? "" : surface.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{1,47}")) {
            return "general-chat";
        }
        return value;
    }

    private static String safeDetail(String detail) {
        String value = detail == null ? "" : detail.replaceAll("\\s+", " ").trim();
        return value.length() <= 160 ? value : value.substring(0, 157) + "...";
    }

    /**
     * 单次运行状态机。方法同步化，兼容流式模型在回调线程上推进阶段。
     */
    public static final class Run {
        private final String surface;
        private final String traceId;
        private final List<Event> events = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private String status = "running";
        private Stage currentStage = Stage.RECEIVED;
        private boolean retryable;
        private boolean fallbackUsed;
        private int contextMessages;

        private Run(String surface, String traceId) {
            this.surface = surface;
            this.traceId = traceId;
            events.add(new Event(Stage.RECEIVED.name().toLowerCase(Locale.ROOT), "done",
                    "请求已进入服务端 Harness", Instant.now()));
        }

        public synchronized Run checkpoint(Stage stage, String detail) {
            requireRunning();
            currentStage = stage;
            events.add(new Event(stage.name().toLowerCase(Locale.ROOT), "done",
                    safeDetail(detail), Instant.now()));
            return this;
        }

        public synchronized Run contextMessages(int count) {
            requireRunning();
            contextMessages = Math.max(0, count);
            return this;
        }

        public synchronized Run fallback(String detail) {
            requireRunning();
            fallbackUsed = true;
            currentStage = Stage.RECOVERY;
            events.add(new Event("recovery", "fallback", safeDetail(detail), Instant.now()));
            return this;
        }

        public synchronized Run warning(String warning) {
            requireRunning();
            String safe = safeDetail(warning);
            if (!safe.isBlank() && !warnings.contains(safe)) {
                warnings.add(safe);
            }
            return this;
        }

        public synchronized Metadata complete() {
            requireRunning();
            status = fallbackUsed || !warnings.isEmpty() ? "degraded" : "completed";
            currentStage = Stage.COMPLETED;
            events.add(new Event("completed", "done", "回答已通过校验并完成", Instant.now()));
            return snapshot();
        }

        public synchronized Metadata fail(boolean canRetry, String detail) {
            if (!isTerminal()) {
                retryable = canRetry;
                status = "failed";
                currentStage = Stage.FAILED;
                events.add(new Event("failed", "failed", safeDetail(detail), Instant.now()));
            }
            return snapshot();
        }

        public synchronized Metadata snapshot() {
            return new Metadata(SCHEMA_VERSION, traceId, surface, status,
                    currentStage.name().toLowerCase(Locale.ROOT), retryable, fallbackUsed,
                    contextMessages, List.copyOf(events), List.copyOf(warnings));
        }

        public String traceId() {
            return traceId;
        }

        private boolean isTerminal() {
            return "completed".equals(status) || "degraded".equals(status) || "failed".equals(status);
        }

        private void requireRunning() {
            if (isTerminal()) {
                throw new IllegalStateException("AI Harness run is already terminal: " + traceId);
            }
        }
    }
}
