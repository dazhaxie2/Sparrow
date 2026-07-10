package com.sparrow.industrychain.application.forum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.infrastructure.event.IndustryChainEventHub;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ForumTimestampTest {

    @Test
    void publishesAgentTimeWithChinaOffset() {
        IndustryChainRepository repository = mock(IndustryChainRepository.class);
        IndustryChainEventHub events = mock(IndustryChainEventHub.class);
        ForumBus forum = new ForumBus(repository, events, mock(ChatModel.class), new ObjectMapper());

        forum.publish(1, 2, 3, ForumEvent.SYSTEM, "started");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).forum(eq(1L), eq(2L), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        OffsetDateTime createdAt = OffsetDateTime.parse((String) event.get("createdAt"));
        assertThat(createdAt.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    }
}
