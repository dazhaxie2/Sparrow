package com.sparrow.ai.application.harness;

import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository.ChatMessageRow;
import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.exception.BizException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded context, output validation and atomic persistence for general AI chat. */
public final class AiChatHarness {

    private static final int MAX_QUESTION_CHARS = 500;
    private static final AiAgentProfile DEFAULT_PROFILE = new AiAgentProfile(
            "sparrow-ai", "tech-tree-guide", "科技图 AI 向导", "",
            "Use the reviewed default prompt", true,
            12, 6_000, 60_000, 5, null, null);

    private final ChatHistoryRepository history;

    public AiChatHarness(ChatHistoryRepository history) {
        this.history = history;
    }

    public record Prepared(AiHarness.Run run, String question, String conversationContext,
                           AiAgentProfile profile) {
    }

    public Prepared prepare(AiHarness.Run run, long userId, Long sessionId, String rawQuestion) {
        return prepare(run, userId, sessionId, rawQuestion, DEFAULT_PROFILE);
    }

    public Prepared prepare(AiHarness.Run run, long userId, Long sessionId, String rawQuestion,
                            AiAgentProfile profile) {
        AiAgentProfile effective = profile == null ? DEFAULT_PROFILE : profile;
        if (!effective.enabled()) {
            throw new BizException(503, effective.displayName() + "已被管理员停用");
        }
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.isBlank()) {
            throw new BizException(400, "问题不能为空");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new BizException(400, "问题不能超过 500 个字符");
        }
        run.checkpoint(AiHarness.Stage.POLICY, "输入边界与 Agent 运行策略通过");

        List<ChatMessageRow> selected = List.of();
        if (sessionId != null) {
            history.findSession(userId, sessionId)
                    .orElseThrow(() -> new BizException(404, "会话不存在"));
            selected = boundedTail(history.messages(userId, sessionId),
                    effective.maxContextMessages(), effective.maxContextChars());
        }
        run.contextMessages(selected.size()).checkpoint(AiHarness.Stage.CONTEXT,
                selected.isEmpty() ? "本轮无历史上下文" : "已装配持久化会话上下文");
        return new Prepared(run, question, toPrompt(selected), effective);
    }

    public String validateAnswer(AiHarness.Run run, String answer) {
        return validateAnswer(run, answer, DEFAULT_PROFILE.maxOutputChars());
    }

    public String validateAnswer(AiHarness.Run run, String answer, int maxAnswerChars) {
        int safeMax = Math.max(500, Math.min(maxAnswerChars, 100_000));
        String normalized = answer == null ? "" : answer
                .replace("\r\n", "\n").replace("\r", "\n").trim()
                .replaceAll("\n{3,}", "\n\n");
        if (normalized.isBlank()) {
            run.warning("模型返回空内容，已使用可靠性兜底");
            normalized = "资料不足以生成可靠回答。";
        }
        if (normalized.length() > safeMax) {
            run.warning("回答超过管理员配置的展示上限，已安全截断");
            normalized = normalized.substring(0, safeMax) + "\n\n> 回答过长，已截断。";
        }
        run.checkpoint(AiHarness.Stage.VALIDATION, "回答非空、格式与长度校验通过");
        return normalized;
    }

    public void persistExchange(AiHarness.Run run, long userId, Long sessionId,
                                String question, String answer, String mode) {
        if (sessionId == null) {
            run.checkpoint(AiHarness.Stage.PERSISTENCE, "无会话 ID，本轮不持久化");
            return;
        }
        history.addExchange(userId, sessionId, question, answer, mode);
        run.checkpoint(AiHarness.Stage.PERSISTENCE, "用户问题与助手回答已原子持久化");
    }

    private String toPrompt(List<ChatMessageRow> messages) {
        if (messages.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder("### 最近会话上下文\n");
        for (ChatMessageRow message : messages) {
            String role = "assistant".equals(message.role()) ? "助手" : "用户";
            prompt.append(role).append(": ").append(message.content().trim()).append('\n');
        }
        return prompt.toString().trim();
    }

    private List<ChatMessageRow> boundedTail(List<ChatMessageRow> all,
                                             int maxMessages, int maxChars) {
        if (all == null || all.isEmpty() || maxMessages <= 0) return List.of();
        int messageLimit = Math.min(maxMessages, 50);
        int charLimit = Math.max(1_000, Math.min(maxChars, 50_000));
        List<ChatMessageRow> reversed = new ArrayList<>();
        int chars = 0;
        for (int i = all.size() - 1; i >= 0 && reversed.size() < messageLimit; i--) {
            ChatMessageRow row = all.get(i);
            if (!("user".equals(row.role()) || "assistant".equals(row.role()))) continue;
            String content = row.content() == null ? "" : row.content().trim();
            if (content.isEmpty()) continue;
            int remaining = charLimit - chars;
            if (remaining <= 0) break;
            if (content.length() > remaining) {
                content = content.substring(content.length() - remaining);
                row = new ChatMessageRow(row.id(), row.sessionId(), row.userId(), row.role(),
                        content, row.mode(), row.createdAt());
            }
            reversed.add(row);
            chars += content.length();
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }
}
