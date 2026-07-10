package com.sparrow.industrychain.application.harness;

import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageIds;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 产业链规划对话的领域内 Harness。 */
public final class IndustryChatHarness {

    private static final int MAX_QUESTION_CHARS = 2_000;
    private static final int MAX_CONTEXT_MESSAGES = 12;
    private static final int MAX_CONTEXT_CHARS = 8_000;
    private static final int MAX_ANSWER_CHARS = 60_000;

    private final IndustryChainRepository repository;

    public IndustryChatHarness(IndustryChainRepository repository) {
        this.repository = repository;
    }

    public record Prepared(AiHarness.Run run, String question, List<MessageRow> history) {
    }

    public Prepared prepare(AiHarness.Run run, String rawQuestion, List<MessageRow> history) {
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.isBlank()) throw new BizException(400, "消息不能为空");
        if (question.length() > MAX_QUESTION_CHARS) throw new BizException(400, "消息不能超过 2000 个字符");
        run.checkpoint(AiHarness.Stage.POLICY, "输入长度、卡片归属与运行状态策略通过");
        List<MessageRow> bounded = boundedTail(history);
        run.contextMessages(bounded.size())
                .checkpoint(AiHarness.Stage.CONTEXT,
                        bounded.isEmpty() ? "本轮无历史上下文" : "已装配最近的卡片对话上下文");
        return new Prepared(run, question, bounded);
    }

    public String validateAnswer(AiHarness.Run run, String answer) {
        String normalized = answer == null ? "" : answer.replace("\r\n", "\n")
                .replace("\r", "\n").trim().replaceAll("\n{3,}", "\n\n");
        if (normalized.isBlank()) {
            run.warning("规划 Agent 返回空内容，已使用可靠性兜底");
            normalized = "暂时无法生成可靠回复，请稍后重试。";
        }
        if (normalized.startsWith("AI 服务尚未配置")) {
            run.fallback("模型未配置，返回可操作的配置提示");
        }
        if (normalized.length() > MAX_ANSWER_CHARS) {
            run.warning("回答超过展示上限，已安全截断");
            normalized = normalized.substring(0, MAX_ANSWER_CHARS) + "\n\n> 回答过长，已截断。";
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

    private List<MessageRow> boundedTail(List<MessageRow> all) {
        if (all == null || all.isEmpty()) return List.of();
        List<MessageRow> reversed = new ArrayList<>();
        int chars = 0;
        for (int i = all.size() - 1; i >= 0 && reversed.size() < MAX_CONTEXT_MESSAGES; i--) {
            MessageRow row = all.get(i);
            String content = row.content() == null ? "" : row.content().trim();
            if (content.isEmpty()) continue;
            int remaining = MAX_CONTEXT_CHARS - chars;
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

