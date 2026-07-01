package com.sparrow.industrychain.application.conversation;

import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.application.card.ResearchCardViews.MessageReply;
import com.sparrow.industrychain.application.card.ResearchCardViews.MessageView;
import com.sparrow.industrychain.application.workflow.IndustryChainResearchOrchestrator;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.CardRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageRow;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResearchConversationService {

    private final IndustryChainRepository repository;
    private final IndustryChainResearchOrchestrator orchestrator;

    public ResearchConversationService(IndustryChainRepository repository,
                                       IndustryChainResearchOrchestrator orchestrator) {
        this.repository = repository;
        this.orchestrator = orchestrator;
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

    private CardRow owned(long userId, long cardId) {
        return repository.findCard(userId, cardId)
                .orElseThrow(() -> new BizException(404, "调研卡片不存在"));
    }

    private MessageView messageView(MessageRow message) {
        return new MessageView(message.id(), message.role(), message.agent(), message.content(), message.createdAt());
    }
}
