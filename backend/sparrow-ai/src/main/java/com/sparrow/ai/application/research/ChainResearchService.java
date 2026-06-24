package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.application.research.ChainResearchViews.*;
import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.ai.infrastructure.config.AiProperties;
import com.sparrow.ai.infrastructure.research.ChainResearchEventHub;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.AttachmentRow;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.CardRow;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.MessageRow;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.RunRow;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.SourceInput;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class ChainResearchService {

    private static final Logger log = LoggerFactory.getLogger(ChainResearchService.class);
    private static final String QUOTA_PREFIX = "sparrow:chain-research:quota:";
    private final ChainResearchRepository repository;
    private final ChainResearchOrchestrator orchestrator;
    private final ChainResearchRunner runner;
    private final ChainResearchEventHub events;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final UserClient userClient;
    private final AiProperties props;

    public ChainResearchService(ChainResearchRepository repository,
                                ChainResearchOrchestrator orchestrator,
                                ChainResearchRunner runner,
                                ChainResearchEventHub events,
                                ObjectMapper objectMapper,
                                StringRedisTemplate redis,
                                UserClient userClient,
                                AiProperties props) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.runner = runner;
        this.events = events;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.userClient = userClient;
        this.props = props;
    }

    public List<CardSummary> list(long userId) {
        return repository.listCards(userId).stream().map(this::summary).toList();
    }

    public CardDetail get(long userId, long cardId) {
        CardRow card = owned(userId, cardId);
        RunView run = repository.activeRun(userId, cardId).map(this::runView).orElse(null);
        JsonNode graph = null;
        if (card.graphJson() != null && !card.graphJson().isBlank()) {
            try {
                graph = objectMapper.readTree(card.graphJson());
            } catch (Exception error) {
                log.warn("读取调研图谱失败: cardId={}", cardId, error);
            }
        }
        return new CardDetail(summary(card), repository.messages(userId, cardId).stream()
                .map(this::messageView).toList(), run, graph, card.reportMd(),
                repository.sources(userId, cardId).stream()
                        .map(source -> new SourceView(source.id(), source.sourceRef(), source.title(), source.url(),
                                source.publisher(), source.snippet())).toList(),
                repository.attachments(userId, cardId).stream()
                        .map(attachment -> new SourceView(attachment.id(), attachment.sourceRef(),
                                attachment.title(), attachment.url(), attachment.publisher(), attachment.snippet()))
                        .toList());
    }

    public CardDetail create(long userId, String title, String brief, List<SourceInput> attachments) {
        long cardId = repository.createCard(userId, title.trim(), blankToNull(brief));
        saveAttachments(userId, cardId, attachments);
        repository.addMessage(userId, cardId, "assistant", "planner",
                "告诉我你最关心的产品、地区、时间范围或企业。我会先帮你收窄问题，确认后再启动联网深度调研。");
        return get(userId, cardId);
    }

    public CardDetail update(long userId, long cardId, String title, String brief, List<SourceInput> attachments) {
        owned(userId, cardId);
        if (repository.activeRun(userId, cardId).isPresent()) throw new BizException(409, "调研运行中，暂不能编辑卡片");
        repository.updateCard(userId, cardId, title.trim(), blankToNull(brief));
        saveAttachments(userId, cardId, attachments);
        return get(userId, cardId);
    }

    /** PDF 上传后追加单个附件，返回更新后的卡片详情。 */
    public CardDetail addAttachment(long userId, long cardId, SourceInput attachment) {
        owned(userId, cardId);
        if (repository.activeRun(userId, cardId).isPresent()) throw new BizException(409, "调研运行中，暂不能添加资料");
        repository.addAttachment(userId, cardId, attachment);
        return get(userId, cardId);
    }

    /** 卡片已有附件数量，用于 PDF 上传时分配下一个来源编号。 */
    public int attachmentCount(long userId, long cardId) {
        owned(userId, cardId);
        return repository.attachmentCount(userId, cardId);
    }

    /** 保存附件列表：分配连续编号 S1..Sk 后覆盖式写入。 */
    private void saveAttachments(long userId, long cardId, List<SourceInput> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            repository.replaceAttachments(userId, cardId, List.of());
            return;
        }
        List<SourceInput> numbered = new java.util.ArrayList<>();
        int index = 1;
        for (SourceInput attachment : attachments) {
            numbered.add(new SourceInput("S" + index++, attachment.title(), attachment.url(),
                    attachment.publisher(), attachment.snippet()));
        }
        repository.replaceAttachments(userId, cardId, numbered);
    }

    public void delete(long userId, long cardId) {
        owned(userId, cardId);
        if (repository.activeRun(userId, cardId).isPresent()) throw new BizException(409, "请先取消运行中的调研任务");
        repository.deleteCard(userId, cardId);
    }

    public MessageReply message(long userId, long cardId, String content) {
        CardRow card = owned(userId, cardId);
        if (repository.activeRun(userId, cardId).isPresent()) throw new BizException(409, "调研运行中，请等待完成后继续对话");
        long userMessageId = repository.addMessage(userId, cardId, "user", null, content.trim());
        List<MessageRow> history = repository.messages(userId, cardId);
        String reply = orchestrator.reply(card.title(), card.brief(), history, content.trim());
        long assistantId = repository.addMessage(userId, cardId, "assistant", "planner", reply);
        List<MessageRow> next = repository.messages(userId, cardId);
        MessageRow userRow = next.stream().filter(item -> item.id() == userMessageId).findFirst().orElseThrow();
        MessageRow assistantRow = next.stream().filter(item -> item.id() == assistantId).findFirst().orElseThrow();
        return new MessageReply(messageView(userRow), messageView(assistantRow));
    }

    public StartRunResult start(long userId, long cardId) {
        owned(userId, cardId);
        long runId = repository.createRun(userId, cardId);
        int remaining;
        try {
            remaining = consumeQuota(userId);
        } catch (RuntimeException error) {
            repository.cancelRun(userId, cardId, runId);
            throw error;
        }
        runner.run(userId, cardId, runId);
        return new StartRunResult(runId, remaining);
    }

    public RunView run(long userId, long cardId, long runId) {
        owned(userId, cardId);
        return repository.findRun(userId, cardId, runId).map(this::runView)
                .orElseThrow(() -> new BizException(404, "调研任务不存在"));
    }

    public void cancel(long userId, long cardId, long runId) {
        run(userId, cardId, runId);
        repository.cancelRun(userId, cardId, runId);
        events.failed(cardId, runId, "任务已取消");
    }

    public SseEmitter subscribe(long userId, long cardId) {
        CardRow card = owned(userId, cardId);
        SseEmitter emitter = events.subscribe(cardId);
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(Map.of(
                    "status", card.status(), "stage", card.currentStage() == null ? "" : card.currentStage(),
                    "progress", card.progress())));
        } catch (Exception error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }

    private int consumeQuota(long userId) {
        int limit = isMember(userId) ? props.chainResearchMemberPerDay() : props.chainResearchFreePerDay();
        if (limit <= 0) throw new BizException(403, "当前账号没有深度调研额度");
        String key = QUOTA_PREFIX + userId + ":" + LocalDate.now();
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1) redis.expire(key, Duration.ofDays(2));
        if (used != null && used > limit) {
            redis.opsForValue().decrement(key);
            throw new BizException(429, "今日深度调研次数已用完");
        }
        return used == null ? -1 : Math.max(0, limit - used.intValue());
    }

    private boolean isMember(long userId) {
        try {
            ApiResponse<Map<String, Object>> response = userClient.membership(userId);
            return response != null && response.data() != null
                    && Boolean.TRUE.equals(response.data().get("member"));
        } catch (Exception error) {
            log.warn("调研会员校验失败，按免费用户处理: userId={}", userId, error);
            return false;
        }
    }

    private CardRow owned(long userId, long cardId) {
        return repository.findCard(userId, cardId)
                .orElseThrow(() -> new BizException(404, "调研卡片不存在"));
    }

    private CardSummary summary(CardRow card) {
        return new CardSummary(card.id(), card.title(), card.brief(), card.status(), card.currentStage(),
                card.progress(), card.nodeCount(), card.edgeCount(), card.lastError(),
                card.createdAt(), card.updatedAt());
    }

    private MessageView messageView(MessageRow message) {
        return new MessageView(message.id(), message.role(), message.agent(), message.content(), message.createdAt());
    }

    private RunView runView(RunRow run) {
        return new RunView(run.id(), run.status(), run.currentStage(), run.progress(), run.errorMessage(),
                run.startedAt(), run.finishedAt());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
