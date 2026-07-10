package com.sparrow.industrychain.application.forum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.infrastructure.event.IndustryChainEventHub;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForumTimestampTest {

    private static ChatModelProvider providerWithoutModel() {
        // 时间戳测试不触发主持人发言,无需真实模型
        return new ChatModelProvider();
    }

    @Test
    void publishesAgentTimeWithChinaOffset() {
        IndustryChainRepository repository = mock(IndustryChainRepository.class);
        IndustryChainEventHub events = mock(IndustryChainEventHub.class);
        ForumBus forum = new ForumBus(repository, events, providerWithoutModel(), new ObjectMapper());

        forum.publish(1, 2, 3, ForumEvent.SYSTEM, "started");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).forum(eq(1L), eq(2L), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        OffsetDateTime createdAt = OffsetDateTime.parse((String) event.get("createdAt"));
        assertThat(createdAt.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    }

    @Test
    void convertsPersistedUtcHistoryToChinaTime() {
        IndustryChainRepository repository = mock(IndustryChainRepository.class);
        IndustryChainEventHub events = mock(IndustryChainEventHub.class);
        ForumBus forum = new ForumBus(repository, events, providerWithoutModel(), new ObjectMapper());
        when(repository.forumEvents(1L, 2L)).thenReturn(List.of(
                new IndustryChainRepository.ForumEventRow(
                        1L, 1L, 2L, 3L, ForumEvent.SYSTEM, "saved",
                        LocalDateTime.of(2026, 7, 10, 12, 33, 0))));

        OffsetDateTime createdAt = OffsetDateTime.parse(forum.history(1, 2).get(0).createdAt());

        assertThat(createdAt.getHour()).isEqualTo(20);
        assertThat(createdAt.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    }
}
