package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.application.research.agent.ChainForumBus;
import com.sparrow.ai.application.research.agent.ForumEvent;
import com.sparrow.ai.infrastructure.research.ChainResearchEventHub;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Multi-Agent 协作核心测试：验证论坛总线的「每 5 条 Agent 发言触发主持人」机制，
 * 以及 Agent 反思总结前能读到最新主持人发言(软耦合)。这是对标 BettaFish
 * host_speech_threshold 的关键协作行为。
 */
class ChainResearchAgentMultiPaperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 攒够 5 条 Agent 发言 → 触发一次主持人发言；再多 5 条 → 第二次。 */
    @Test
    void triggersHostAfterEveryFiveAgentSpeeches() {
        ChainResearchRepository repo = mock(ChainResearchRepository.class);
        ChainResearchEventHub hub = mock(ChainResearchEventHub.class);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("主持人总结");
        ChainForumBus bus = new ChainForumBus(repo, hub, model, MAPPER);

        for (int i = 0; i < 5; i++) bus.publish(1, 10, 1, ForumEvent.INDUSTRY, "发言 " + i);
        verify(model, times(1)).chat(anyString());

        for (int i = 0; i < 5; i++) bus.publish(1, 10, 1, ForumEvent.QUERY, "发言 " + i);
        verify(model, times(2)).chat(anyString());
    }

    /** 主持人发言写入后，Agent 可通过 latestHostSpeech 读到(软耦合注入 prompt)。 */
    @Test
    void exposesLatestHostSpeechForAgents() {
        ChainResearchRepository repo = mock(ChainResearchRepository.class);
        ChainResearchEventHub hub = mock(ChainResearchEventHub.class);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("最新主持人引导");
        ChainForumBus bus = new ChainForumBus(repo, hub, model, MAPPER);

        assertThat(bus.latestHostSpeech(10)).isEmpty();
        for (int i = 0; i < 5; i++) bus.publish(1, 10, 1, ForumEvent.INDUSTRY, "发言 " + i);
        assertThat(bus.latestHostSpeech(10)).contains("最新主持人引导");
    }

    /** 非发言方(SYSTEM)不累计触发主持人。 */
    @Test
    void systemMessagesDoNotTriggerHost() {
        ChainResearchRepository repo = mock(ChainResearchRepository.class);
        ChainResearchEventHub hub = mock(ChainResearchEventHub.class);
        ChatModel model = mock(ChatModel.class);
        ChainForumBus bus = new ChainForumBus(repo, hub, model, MAPPER);

        for (int i = 0; i < 6; i++) bus.publish(1, 10, 1, ForumEvent.SYSTEM, "系统 " + i);
        verify(model, times(0)).chat(anyString());
    }

    /** host 发言会一并落库与广播(供前端实时渲染论坛流)。 */
    @Test
    void hostEventsArePersistedAndBroadcast() {
        ChainResearchRepository repo = mock(ChainResearchRepository.class);
        ChainResearchEventHub hub = mock(ChainResearchEventHub.class);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("host 内容");
        ChainForumBus bus = new ChainForumBus(repo, hub, model, MAPPER);

        for (int i = 0; i < 5; i++) bus.publish(1, 10, 1, ForumEvent.INSIGHT, "i" + i);
        // 6 条落库(5 Agent + 1 Host)，全部广播
        verify(repo, atLeast(6)).addForumEvent(eq(1L), eq(1L), eq(10L), anyString(), anyString());
        verify(hub, atLeast(6)).forum(eq(1L), eq(10L), any());
    }
}
