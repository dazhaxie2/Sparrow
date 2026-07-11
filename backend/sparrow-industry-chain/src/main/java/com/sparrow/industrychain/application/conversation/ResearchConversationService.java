package com.sparrow.industrychain.application.conversation;

import com.sparrow.common.exception.BizException;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.ai.AiHarness.Metadata;
import com.sparrow.industrychain.application.card.ResearchCardViews.MessageReply;
import com.sparrow.industrychain.application.card.ResearchCardViews.MessageView;
import com.sparrow.industrychain.application.workflow.IndustryChainResearchOrchestrator;
import com.sparrow.industrychain.application.harness.IndustryChatHarness;
import com.sparrow.industrychain.application.harness.IndustryChatHarness.Prepared;
import com.sparrow.industrychain.application.config.IndustryAgentConfigService;
import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.CardRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Service
public class ResearchConversationService {

    private static final Logger log = LoggerFactory.getLogger(ResearchConversationService.class);

    private final IndustryChainRepository repository;
    private final IndustryChainResearchOrchestrator orchestrator;
    private final IndustryChatHarness chatHarness;
    private IndustryAgentConfigService agentConfigs;

    public ResearchConversationService(IndustryChainRepository repository,
                                       IndustryChainResearchOrchestrator orchestrator) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.chatHarness = new IndustryChatHarness(repository);
    }

    @Autowired(required = false)
    void setAgentConfigs(IndustryAgentConfigService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    public MessageReply message(long userId, long cardId, String content) {
        AiHarness.Run harness = AiHarness.start("industry-chain-planning");
        try {
            CardRow card = owned(userId, cardId);
            if (repository.activeRun(userId, cardId).isPresent()) {
                throw new BizException(409, "调研运行中，请等待完成后继续对话");
            }
            AiAgentProfile profile = agentConfigs == null ? null
                    : agentConfigs.requireEnabled(IndustryAgentConfigService.PLANNING_CHAT);
            Prepared prepared = profile == null
                    ? chatHarness.prepare(harness, content, repository.messages(userId, cardId))
                    : chatHarness.prepare(harness, content, repository.messages(userId, cardId), profile);
            harness.checkpoint(AiHarness.Stage.EXECUTION, "规划 Agent 正在基于卡片与历史上下文回复");
            String rawReply = orchestrator.reply(card.title(), card.brief(), prepared.history(), prepared.question());
            String reply = chatHarness.validateAnswer(harness, rawReply,
                    prepared.profile().maxOutputChars());
            MessageIds ids = chatHarness.persistExchange(harness, userId, cardId, prepared.question(), reply);
            List<MessageRow> next = repository.messages(userId, cardId);
            MessageRow userRow = next.stream().filter(item -> item.id() == ids.userMessageId()).findFirst().orElseThrow();
            MessageRow assistantRow = next.stream().filter(item -> item.id() == ids.assistantMessageId()).findFirst().orElseThrow();
            Metadata metadata = harness.complete();
            return new MessageReply(messageView(userRow), messageView(assistantRow), metadata);
        } catch (BizException error) {
            Metadata failed = harness.fail(error.getCode() >= 500, "产业链规划对话未完成");
            log.warn("产业链规划对话被拒绝 [traceId={} cardId={} code={}]: {}",
                    failed.traceId(), cardId, error.getCode(), AiHarness.safeFailure(error));
            throw new BizException(error.getCode(), error.getMessage() + "（追踪 ID: " + failed.traceId() + "）");
        } catch (Exception error) {
            Metadata failed = harness.fail(true, "产业链规划对话执行失败");
            log.warn("产业链规划对话失败 [traceId={} cardId={}]: {}",
                    failed.traceId(), cardId, AiHarness.safeFailure(error));
            throw new BizException(503, "规划 Agent 暂时不可用（追踪 ID: " + failed.traceId() + "）");
        }
    }

    private CardRow owned(long userId, long cardId) {
        return repository.findCard(userId, cardId)
                .orElseThrow(() -> new BizException(404, "调研卡片不存在"));
    }

    private MessageView messageView(MessageRow message) {
        return new MessageView(message.id(), message.role(), message.agent(), message.content(), message.createdAt());
    }
}
