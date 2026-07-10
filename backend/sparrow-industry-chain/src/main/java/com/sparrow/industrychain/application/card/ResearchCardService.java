package com.sparrow.industrychain.application.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.application.card.ResearchCardViews.*;
import com.sparrow.industrychain.application.conversation.ResearchConversationService;
import com.sparrow.industrychain.application.run.ResearchRunService;
import com.sparrow.industrychain.application.source.SourceRefAllocator;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.CardRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.RunRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.SourceInput;
import com.sparrow.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.time.ZoneId;

/**
 * 产业链深度调研应用服务。
 * 编排卡片生命周期、消息对话、调研任务执行与配额控制。
 * 对外暴露给 Controller，内部协调 Orchestrator、Runner、Repository 与 EventHub。
 */
@Service
public class ResearchCardService {

    private static final Logger log = LoggerFactory.getLogger(ResearchCardService.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private final IndustryChainRepository repository;
    private final ResearchConversationService conversationService;
    private final ResearchRunService runService;
    private final ObjectMapper objectMapper;
    private final SourceRefAllocator sourceRefs;

    /**
     * 构造函数。
     *
     * @param repository    调研数据持久化
     * @param conversationService 对话服务
     * @param runService    调研运行服务
     * @param objectMapper  JSON 序列化
     * @param sourceRefs    来源编号分配器
     */
    public ResearchCardService(IndustryChainRepository repository,
                                ResearchConversationService conversationService,
                                ResearchRunService runService,
                                ObjectMapper objectMapper,
                                SourceRefAllocator sourceRefs) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.runService = runService;
        this.objectMapper = objectMapper;
        this.sourceRefs = sourceRefs;
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
                        event.content(), event.createdAt() == null ? null : event.createdAt()
                                .atZone(CHINA_ZONE).toOffsetDateTime().toString()))
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
        repository.replaceAttachments(userId, cardId, sourceRefs.renumber(attachments));
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
        return conversationService.message(userId, cardId, content);
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
        return runService.start(userId, cardId);
    }

    /** 从最近一次失败任务的持久化检查点继续，不重复扣减配额。 */
    public ResumeRunResult resume(long userId, long cardId) {
        return runService.resume(userId, cardId);
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
        return runService.run(userId, cardId, runId);
    }

    /**
     * 取消运行中的调研任务。
     *
     * @param userId 用户 ID
     * @param cardId 卡片 ID
     * @param runId  运行 ID
     */
    public void cancel(long userId, long cardId, long runId) {
        runService.cancel(userId, cardId, runId);
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
        return runService.subscribe(userId, cardId);
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

