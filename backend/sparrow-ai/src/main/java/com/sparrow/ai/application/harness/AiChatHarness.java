package com.sparrow.ai.application.harness;

import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository;
import com.sparrow.ai.infrastructure.persistence.ChatHistoryRepository.ChatMessageRow;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.exception.BizException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 通用 AI 对话的上下文、策略、校验与持久化 Harness。 */
public final class AiChatHarness {

    private static final int MAX_QUESTION_CHARS = 500;
    private static final int MAX_CONTEXT_MESSAGES = 12;
    private static final int MAX_CONTEXT_CHARS = 6_000;
    private static final int MAX_ANSWER_CHARS = 60_000;

    private final ChatHistoryRepository history;

    public AiChatHarness(ChatHistoryRepository history) {
        this.history = history;
    }

    public record Prepared(AiHarness.Run run, String question, String conversationContext) {
    }

    /** 校验输入和会话归属，并从持久化历史中装配有界上下文。 */
    public Prepared prepare(AiHarness.Run run, long userId, Long sessionId, String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.isBlank()) {
            throw new BizException(400, "问题不能为空");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new BizException(400, "问题不能超过 500 个字符");
        }
        run.checkpoint(AiHarness.Stage.POLICY, "输入长度、会话边界与安全策略通过");

        List<ChatMessageRow> selected = List.of();
        if (sessionId != null) {
            history.findSession(userId, sessionId)
                    .orElseThrow(() -> new BizException(404, "会话不存在"));
            selected = boundedTail(history.messages(userId, sessionId));
        }
        run.contextMessages(selected.size())
                .checkpoint(AiHarness.Stage.CONTEXT,
                        selected.isEmpty() ? "本轮无历史上下文" : "已装配最近的持久化会话上下文");
        return new Prepared(run, question, toPrompt(selected));
    }

    /** 输出格式与体积校验；任何修复都会进入 Harness warnings。 */
    public String validateAnswer(AiHarness.Run run, String answer) {
        String normalized = answer == null ? "" : answer
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim()
                .replaceAll("\n{3,}", "\n\n");
        if (normalized.isBlank()) {
            run.warning("模型返回空内容，已使用可靠性兜底");
            normalized = "资料不足以生成可靠回答。";
        }
        if (normalized.length() > MAX_ANSWER_CHARS) {
            run.warning("回答超过展示上限，已安全截断");
            normalized = normalized.substring(0, MAX_ANSWER_CHARS) + "\n\n> 回答过长，已截断。";
        }
        run.checkpoint(AiHarness.Stage.VALIDATION, "回答非空、格式与长度校验通过");
        return normalized;
    }

    /** 成对、原子地保存 user/assistant，避免流中断留下孤儿消息。 */
    public void persistExchange(AiHarness.Run run, long userId, Long sessionId,
                                String question, String answer, String mode) {
        if (sessionId == null) {
            run.checkpoint(AiHarness.Stage.PERSISTENCE, "无会话 ID，本轮不持久化");
            return;
        }
        history.addExchange(userId, sessionId, question, answer, mode);
        run.checkpoint(AiHarness.Stage.PERSISTENCE, "用户问题与助手回答已原子持久化");
    }

    /** 给模型的历史片段不包含数据库 ID、时间、trace 或任何服务端配置。 */
    private String toPrompt(List<ChatMessageRow> messages) {
        if (messages.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder("### 最近会话上下文\n");
        for (ChatMessageRow message : messages) {
            String role = "assistant".equals(message.role()) ? "助手" : "用户";
            prompt.append(role).append(": ").append(message.content().trim()).append("\n");
        }
        return prompt.toString().trim();
    }

    private List<ChatMessageRow> boundedTail(List<ChatMessageRow> all) {
        if (all == null || all.isEmpty()) return List.of();
        List<ChatMessageRow> reversed = new ArrayList<>();
        int chars = 0;
        for (int i = all.size() - 1; i >= 0 && reversed.size() < MAX_CONTEXT_MESSAGES; i--) {
            ChatMessageRow row = all.get(i);
            if (!("user".equals(row.role()) || "assistant".equals(row.role()))) continue;
            String content = row.content() == null ? "" : row.content().trim();
            if (content.isEmpty()) continue;
            int remaining = MAX_CONTEXT_CHARS - chars;
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

