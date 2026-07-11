package com.sparrow.industrychain.application.harness;

import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageIds;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Domain harness for the industry-chain planning conversation. */
public final class IndustryChatHarness {

    private static final int MAX_QUESTION_CHARS = 2_000;
    private static final AiAgentProfile DEFAULT = new AiAgentProfile(
            "sparrow-industry-chain", "chain-planning-chat", "调研规划对话 Agent", "",
            "Use reviewed default", true, 12, 8_000, 60_000, 3, null, null);

    private final IndustryChainRepository repository;

    public IndustryChatHarness(IndustryChainRepository repository) {
        this.repository = repository;
    }

    public record Prepared(AiHarness.Run run, String question, List<MessageRow> history,
                           AiAgentProfile profile) {
    }

    public Prepared prepare(AiHarness.Run run, String rawQuestion, List<MessageRow> history) {
        return prepare(run, rawQuestion, history, DEFAULT);
    }

    public Prepared prepare(AiHarness.Run run, String rawQuestion, List<MessageRow> history,
                            AiAgentProfile profile) {
        AiAgentProfile effective = profile == null ? DEFAULT : profile;
        if (!effective.enabled()) {
            throw new BizException(503, effective.displayName() + "已被管理员停用");
        }
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.isBlank()) throw new BizException(400, "消息不能为空");
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new BizException(400, "消息不能超过 2000 个字符");
        }
        run.checkpoint(AiHarness.Stage.POLICY, "输入、卡片归属与 Agent 运行策略通过");
        List<MessageRow> bounded = boundedTail(history,
                effective.maxContextMessages(), effective.maxContextChars());
        run.contextMessages(bounded.size()).checkpoint(AiHarness.Stage.CONTEXT,
                bounded.isEmpty() ? "本轮无历史上下文" : "已装配卡片持久化对话上下文");
        return new Prepared(run, question, bounded, effective);
    }

    public String validateAnswer(AiHarness.Run run, String answer) {
        return validateAnswer(run, answer, DEFAULT.maxOutputChars());
    }

    public String validateAnswer(AiHarness.Run run, String answer, int maxOutputChars) {
        int safeMax = Math.max(500, Math.min(maxOutputChars, 100_000));
        String normalized = answer == null ? "" : answer.replace("\r\n", "\n")
                .replace("\r", "\n").trim().replaceAll("\n{3,}", "\n\n");
        if (normalized.isBlank()) {
            run.warning("规划 Agent 返回空内容，已使用可靠性兜底");
            normalized = "暂时无法生成可靠回复，请稍后重试。";
        }
        if (normalized.startsWith("AI 服务尚未配置")) {
            run.fallback("模型未配置，返回可操作的配置提示");
        }
        if (normalized.length() > safeMax) {
            run.warning("回答超过管理员配置的展示上限，已安全截断");
            normalized = normalized.substring(0, safeMax) + "\n\n> 回答过长，已截断。";
        }
        run.checkpoint(AiHarness.Stage.VALIDATION, "规划回复非空、格式与长度校验通过");
        return normalized;
    }

    public MessageIds persistExchange(AiHarness.Run run, long userId, long cardId,
                                      String question, String answer) {
        MessageIds ids = repository.addExchange(userId, cardId, question, answer);
        run.checkpoint(AiHarness.Stage.PERSISTENCE, "用户消息与规划 Agent 回复已原子持久化");
        return ids;
    }

    private List<MessageRow> boundedTail(List<MessageRow> all, int maxMessages, int maxChars) {
        if (all == null || all.isEmpty() || maxMessages <= 0) return List.of();
        int messageLimit = Math.min(maxMessages, 50);
        int charLimit = Math.max(1_000, Math.min(maxChars, 50_000));
        List<MessageRow> reversed = new ArrayList<>();
        int chars = 0;
        for (int i = all.size() - 1; i >= 0 && reversed.size() < messageLimit; i--) {
            MessageRow row = all.get(i);
            String content = row.content() == null ? "" : row.content().trim();
            if (content.isEmpty()) continue;
            int remaining = charLimit - chars;
            if (remaining <= 0) break;
            if (content.length() > remaining) {
                content = content.substring(content.length() - remaining);
                row = new MessageRow(row.id(), row.cardId(), row.role(), row.agent(),
                        content, row.createdAt());
            }
            reversed.add(row);
            chars += content.length();
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }
}
