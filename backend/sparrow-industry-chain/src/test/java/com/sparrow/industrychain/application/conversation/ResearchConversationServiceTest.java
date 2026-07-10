package com.sparrow.industrychain.application.conversation;

import com.sparrow.industrychain.application.card.ResearchCardViews.MessageReply;
import com.sparrow.industrychain.application.workflow.IndustryChainResearchOrchestrator;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.CardRow;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageIds;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.MessageRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchConversationServiceTest {

    private final IndustryChainRepository repository = mock(IndustryChainRepository.class);
    private final IndustryChainResearchOrchestrator orchestrator = mock(IndustryChainResearchOrchestrator.class);
    private final ResearchConversationService service = new ResearchConversationService(repository, orchestrator);

    @Test
    void boundsPersistedContextAndStoresCompleteExchangeAtomically() {
        long userId = 42L;
        long cardId = 9L;
        LocalDateTime now = LocalDateTime.now();
        when(repository.findCard(userId, cardId)).thenReturn(Optional.of(card(cardId, userId, now)));
        when(repository.activeRun(userId, cardId)).thenReturn(Optional.empty());
        List<MessageRow> history = new ArrayList<>();
        for (long i = 1; i <= 16; i++) {
            history.add(new MessageRow(i, cardId, i % 2 == 0 ? "assistant" : "user",
                    i % 2 == 0 ? "planner" : null, "历史消息 " + i, now));
        }
        MessageRow user = new MessageRow(101L, cardId, "user", null, "关注中国市场", now);
        MessageRow assistant = new MessageRow(102L, cardId, "assistant", "planner", "请补充时间范围", now);
        when(repository.messages(userId, cardId)).thenReturn(history, List.of(user, assistant));
        when(orchestrator.reply(eq("机器人"), eq("产业链调研"), anyList(), eq("关注中国市场")))
                .thenReturn("请补充时间范围");
        when(repository.addExchange(userId, cardId, "关注中国市场", "请补充时间范围"))
                .thenReturn(new MessageIds(101L, 102L));

        MessageReply reply = service.message(userId, cardId, "  关注中国市场  ");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageRow>> context = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).reply(eq("机器人"), eq("产业链调研"), context.capture(), eq("关注中国市场"));
        assertEquals(12, context.getValue().size());
        assertEquals("历史消息 5", context.getValue().get(0).content());
        verify(repository).addExchange(userId, cardId, "关注中国市场", "请补充时间范围");
        assertEquals(101L, reply.userMessage().id());
        assertEquals(102L, reply.assistantMessage().id());
        assertEquals("completed", reply.harness().status());
        assertEquals("industry-chain-planning", reply.harness().surface());
        assertEquals(12, reply.harness().contextMessages());
    }

    @Test
    void modelConfigurationFallbackIsVisibleInHarnessMetadata() {
        long userId = 42L;
        long cardId = 9L;
        LocalDateTime now = LocalDateTime.now();
        when(repository.findCard(userId, cardId)).thenReturn(Optional.of(card(cardId, userId, now)));
        when(repository.activeRun(userId, cardId)).thenReturn(Optional.empty());
        MessageRow user = new MessageRow(201L, cardId, "user", null, "继续", now);
        MessageRow assistant = new MessageRow(202L, cardId, "assistant", "planner",
                "AI 服务尚未配置，请配置模型后再继续对话。", now);
        when(repository.messages(userId, cardId)).thenReturn(List.of(), List.of(user, assistant));
        when(orchestrator.reply(eq("机器人"), eq("产业链调研"), anyList(), eq("继续")))
                .thenReturn(assistant.content());
        when(repository.addExchange(userId, cardId, "继续", assistant.content()))
                .thenReturn(new MessageIds(201L, 202L));

        MessageReply reply = service.message(userId, cardId, "继续");

        assertEquals("degraded", reply.harness().status());
        assertTrue(reply.harness().fallbackUsed());
    }

    private CardRow card(long cardId, long userId, LocalDateTime now) {
        return new CardRow(cardId, userId, "机器人", "产业链调研", "DRAFT",
                null, 0, 0, 0, null, null, null, null, now, now);
    }
}

