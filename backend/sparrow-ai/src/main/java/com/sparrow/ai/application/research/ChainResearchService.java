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

/**
 * 产业链深度调研应用服务。
 * 编排卡片生命周期、消息对话、调研任务执行与配额控制。
 * 对外暴露给 Controller，内部协调 Orchestrator、Runner、Repository 与 EventHub。
 */
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

    /**
     * 构造函数。
     *
     * @param repository    调研数据持久化
     * @param orchestrator  调研编排器(规划/搜索/核验/建图/写报告)
     * @param runner        异步任务执行器
     * @param events        SSE 事件广播中心
     * @param objectMapper  JSON 序列化
     * @param redis         Redis 模板(配额计数)
     * @param userClient    用户服务客户端(会员校验)
     * @param props         AI 配置属性(免费/会员每日额度)
     */
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

    /**
     * 查询用户的所有调研卡片列表。
     *
     * @param userId 用户 ID
     * @return 卡片摘要列表，按更新时间倒序
     */
    public List<CardSummary> list(long userId) {
        return repository.listCards(userId).stream().map(this::summary).toList();
    }

    /**
     * 获取调研卡片详情。
     * 包含卡片基本信息、对话历史、运行中任务、图谱 JSON、报告 Markdown、来源与附件。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @return 卡片详情
     */
    public CardDetail get(long userId, long cardId) {
        CardRow card = owned(userId, cardId);
        RunView run = repository.activeRun(userId, cardId).map(this::runView).orElse(null);
        JsonNode graph = parseJsonQuietly(card.graphJson(), "读取调研图谱失败: cardId=" + cardId);
        JsonNode reportIr = parseJsonQuietly(card.reportIr(), "读取调研报告 IR 失败: cardId=" + cardId);
        return new CardDetail(summary(card), repository.messages(userId, cardId).stream()
                .map(this::messageView).toList(), run, graph, reportIr, card.reportMd(),
                repository.sources(userId, cardId).stream()
                        .map(source -> new SourceView(source.id(), source.sourceRef(), source.title(), source.url(),
                                source.publisher(), source.snippet())).toList(),
                repository.attachments(userId, cardId).stream()
                        .map(attachment -> new SourceView(attachment.id(), attachment.sourceRef(),
                                attachment.title(), attachment.url(), attachment.publisher(), attachment.snippet()))
                        .toList());
    }

    /** 安全解析 JSON 字符串为 JsonNode，失败时按 warnContext 记录并返回 null。 */
    private JsonNode parseJsonQuietly(String json, String warnContext) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception error) {
            log.warn("{}: {}", warnContext, error.getMessage());
            return null;
        }
    }

    /** 查询卡片最近一次运行的论坛事件(用于工作台初次加载还原协作流)。 */
    public List<ForumEventView> forumEvents(long userId, long cardId) {
        CardRow card = owned(userId, cardId);
        Long runId = repository.activeRun(userId, cardId).map(RunRow -> RunRow.id())
                .orElseGet(() -> repository.lastRunId(userId, cardId));
        if (runId == null) return List.of();
        return repository.forumEvents(cardId, runId).stream()
                .map(event -> new ForumEventView(event.id(), event.source(), sourceText(event.source()),
                        event.content(), event.createdAt() == null ? null : event.createdAt().toString()))
                .toList();
    }

    /** 来源标签中文显示(与前端气泡样式对齐)。 */
    private String sourceText(String source) {
        return switch (source == null ? "" : source) {
            case "INDUSTRY" -> "行业 Agent";
            case "QUERY" -> "检索 Agent";
            case "INSIGHT" -> "洞察 Agent";
            case "HOST" -> "论坛主持人";
            case "SYSTEM" -> "系统";
            default -> source == null ? "" : source;
        };
    }

    /**
     * 创建新的调研卡片。
     *
     * @param userId     用户 ID
     * @param title      卡片标题
     * @param brief      卡片简述
     * @param attachments 用户提供的资料来源
     * @return 创建后的卡片详情
     */
    public CardDetail create(long userId, String title, String brief, List<SourceInput> attachments) {
        long cardId = repository.createCard(userId, title.trim(), blankToNull(brief));
        saveAttachments(userId, cardId, attachments);
        repository.addMessage(userId, cardId, "assistant", "planner",
                "告诉我你最关心的产品、地区、时间范围或企业。我会先帮你收窄问题，确认后再启动联网深度调研。");
        return get(userId, cardId);
    }

    /**
     * 更新调研卡片基本信息与资料。
     *
     * @param userId      用户 ID
     * @param cardId      卡片 ID
     * @param title       新标题
     * @param brief       新简述
     * @param attachments 新资料列表
     * @return 更新后的卡片详情
     * @throws BizException 409 当有运行中的调研任务时
     */
    public CardDetail update(long userId, long cardId, String title, String brief, List<SourceInput> attachments) {
        owned(userId, cardId);
        if (repository.activeRun(userId, cardId).isPresent()) throw new BizException(409, "调研运行中，暂不能编辑卡片");
        repository.updateCard(userId, cardId, title.trim(), blankToNull(brief));
        saveAttachments(userId, cardId, attachments);
        return get(userId, cardId);
    }

    /**
     * PDF 上传后追加单个附件，返回更新后的卡片详情。
     *
     * @param userId     用户 ID
     * @param cardId     卡片 ID
     * @param attachment 附件信息
     * @return 更新后的卡片详情
     * @throws BizException 409 当有运行中的调研任务时
     */
    public CardDetail addAttachment(long userId, long cardId, SourceInput attachment) {
        owned(userId, cardId);
        if (repository.activeRun(userId, cardId).isPresent()) throw new BizException(409, "调研运行中，暂不能添加资料");
        repository.addAttachment(userId, cardId, attachment);
        return get(userId, cardId);
    }

    /**
     * 获取卡片已有附件数量，用于 PDF 上传时分配下一个来源编号。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @return 附件数量
     */
    public int attachmentCount(long userId, long cardId) {
        owned(userId, cardId);
        return repository.attachmentCount(userId, cardId);
    }

    /**
     * 保存附件列表：分配连续编号 S1..Sk 后覆盖式写入。
     *
     * @param userId      用户 ID
     * @param cardId      卡片 ID
     * @param attachments 附件列表
     */
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

    /**
     * 删除调研卡片。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @throws BizException 409 当有运行中的调研任务时
     */
    public void delete(long userId, long cardId) {
        owned(userId, cardId);
        if (repository.activeRun(userId, cardId).isPresent()) throw new BizException(409, "请先取消运行中的调研任务");
        repository.deleteCard(userId, cardId);
    }

    /**
     * 发送用户消息并获取规划 Agent 回复。
     * 仅在无运行中任务时可用，用于对话式收窄调研范围。
     *
     * @param userId  用户 ID
     * @param cardId  卡片 ID
     * @param content 用户消息内容
     * @return 用户消息与助手回复
     * @throws BizException 409 当有运行中的调研任务时
     */
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

    /**
     * 启动深度调研任务。
     * 扣减配额、创建运行记录、异步提交给 Runner 执行。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @return 运行 ID 与剩余配额
     * @throws BizException 429 配额用完、403 无额度
     */
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

    /**
     * 查询调研任务运行状态。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @param runId  运行 ID
     * @return 运行视图
     * @throws BizException 404 任务不存在
     */
    public RunView run(long userId, long cardId, long runId) {
        owned(userId, cardId);
        return repository.findRun(userId, cardId, runId).map(this::runView)
                .orElseThrow(() -> new BizException(404, "调研任务不存在"));
    }

    /**
     * 取消运行中的调研任务。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @param runId  运行 ID
     */
    public void cancel(long userId, long cardId, long runId) {
        run(userId, cardId, runId);
        repository.cancelRun(userId, cardId, runId);
        events.failed(cardId, runId, "任务已取消");
    }

    /**
     * 订阅调研进度 SSE 事件流。
     * 首帧推送当前卡片快照，后续推送 progress/completed/failed 事件。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @return SSE 发射器
     */
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

    /**
     * 消耗用户深度调研配额。
     * 会员与免费用户有不同每日上限，Redis 计数 + 过期自动清理。
     *
     * @param userId 用户 ID
     * @return 剩余配额，会员返回 -1
     * @throws BizException 403 无额度、429 配额用完
     */
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

    /**
     * 检查用户是否为会员。
     * 远程调用失败时按非会员处理，保证可用性。
     *
     * @param userId 用户 ID
     * @return true 表示会员
     */
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

    /**
     * 校验卡片归属，不存在抛 404。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @return 卡片行数据
     * @throws BizException 404 卡片不存在
     */
    private CardRow owned(long userId, long cardId) {
        return repository.findCard(userId, cardId)
                .orElseThrow(() -> new BizException(404, "调研卡片不存在"));
    }

    /**
     * 转换为卡片摘要视图。
     *
     * @param card 卡片行数据
     * @return 卡片摘要
     */
    private CardSummary summary(CardRow card) {
        return new CardSummary(card.id(), card.title(), card.brief(), card.status(), card.currentStage(),
                card.progress(), card.nodeCount(), card.edgeCount(), card.lastError(),
                card.createdAt(), card.updatedAt());
    }

    /**
     * 转换为消息视图。
     *
     * @param message 消息行数据
     * @return 消息视图
     */
    private MessageView messageView(MessageRow message) {
        return new MessageView(message.id(), message.role(), message.agent(), message.content(), message.createdAt());
    }

    /**
     * 转换为运行视图。
     *
     * @param run 运行行数据
     * @return 运行视图
     */
    private RunView runView(RunRow run) {
        return new RunView(run.id(), run.status(), run.currentStage(), run.progress(), run.errorMessage(),
                run.startedAt(), run.finishedAt());
    }

    /**
     * 空白字符串转 null。
     *
     * @param value 输入字符串
     * @return 去首尾空白后的字符串，空白则返回 null
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
