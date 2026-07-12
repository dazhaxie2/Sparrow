package com.sparrow.industrychain.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.application.forum.ForumBus;
import com.sparrow.industrychain.application.graph.ResearchGraphExtractor;
import com.sparrow.industrychain.application.report.ResearchReportBuilder;
import com.sparrow.industrychain.infrastructure.event.IndustryChainEventHub;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产业链深度调研编排器单元测试 —— 覆盖完整 pipeline(planning→并行 agents→source merge→
 * evidence→graph→report)及两个致命分支(503 LLM 未配置 / 502 空来源)。
 *
 * <p>所有外部依赖(ChatModel / WebSearchClient / GraphExtractor / ReportBuilder)用 Mockito 替换,
 * Executor 用 Runnable::run 同步执行保证确定性。对应 live run 依赖 sogou 联网 + LLM,
 * 无法在测试环境稳定满足,故用单测覆盖编排逻辑。</p>
 */
class IndustryChainResearchOrchestratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatModelProvider chat;
    private ChatModel chatModel;
    private WebSearchClient webSearch;
    private ForumBus forum;
    private ResearchGraphExtractor graphExtractor;
    private ResearchReportBuilder reportBuilder;
    // 同步执行器:CompletableFuture.supplyAsync 在当前线程跑,保证测试确定性
    private final Executor syncExecutor = Runnable::run;

    private IndustryChainResearchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        chat = mock(ChatModelProvider.class);
        chatModel = mock(ChatModel.class);
        webSearch = mock(WebSearchClient.class);
        graphExtractor = mock(ResearchGraphExtractor.class);
        reportBuilder = mock(ResearchReportBuilder.class);

        // ForumBus 用真实实现 + mock ChatModel(主持人发言) + mock 仓储/事件。
        // ChatModelProvider 用真实实例持有 mock 模型(ForumBus 经 provider.model() 取主持人模型)。
        IndustryChainRepository repo = mock(IndustryChainRepository.class);
        IndustryChainEventHub hub = mock(IndustryChainEventHub.class);
        ChatModelProvider forumProvider = new ChatModelProvider(chatModel);
        forum = new ForumBus(repo, hub, forumProvider, MAPPER, Runnable::run);

        orchestrator = new IndustryChainResearchOrchestrator(chat, MAPPER, webSearch, forum,
                graphExtractor, reportBuilder, syncExecutor);
    }

    // ── happy path: 完整 pipeline 走通 ──

    @Test
    void researchCompletesFullPipelineAndReturnsResult() {
        stubLlmConfigured();
        // planning: 规划产出含「补充查询:」段,触发 extractQueries
        when(chat.chat(anyString())).thenReturn("调研计划\n补充查询：\n- 钢铁市场份额\n- 政策");
        // agent summarize/reflect: ChatModel.chat 返回固定文本
        when(chatModel.chat(anyString())).thenReturn("Agent 总结发现");
        // 三角色 Agent 联网各返回 2 条来源(URL 去重后合并)
        when(webSearch.search(anyString(), any(), anyList(), anyInt()))
                .thenReturn(List.of(
                        src("http://a.com/1", "来源A1"),
                        src("http://a.com/2", "来源A2")));
        JsonNode graph = graphJson(3, 4);
        when(graphExtractor.extract(anyString(), anyString(), anyList())).thenReturn(graph);
        when(reportBuilder.build(anyString(), anyString(), any(JsonNode.class), anyList(), anyString()))
                .thenReturn(new ResearchReportBuilder.ReportResult("{\"doc\":\"ir\"}", "# 报告"));

        List<StageUpdate> stages = new ArrayList<>();
        IndustryChainResearchOrchestrator.ResearchResult result = orchestrator.research(
                "钢铁产业链", "调研钢铁上中下游", List.of(), List.of(src("http://u.com", "用户资料")),
                1L, 10L, 100L,
                (stage, progress, msg) -> stages.add(new StageUpdate(stage, progress, msg)));

        // 结果非空,含图谱 + 报告
        assertThat(result.graphJson()).contains("nodes");
        assertThat(result.reportIrJson()).isEqualTo("{\"doc\":\"ir\"}");
        assertThat(result.reportMarkdown()).isEqualTo("# 报告");
        // 来源合并:用户资料 S1 + 联网去重后(三 agent 各 2 条,URL 全相同只保留 2 条) = 3
        assertThat(result.sources()).hasSize(3);
        assertThat(result.sources().get(0).sourceRef()).isEqualTo("S1");  // 用户资料优先
        assertThat(result.sources().get(1).sourceRef()).isEqualTo("S2");  // 联网接续
        assertThat(result.nodeCount()).isEqualTo(3);
        assertThat(result.edgeCount()).isEqualTo(4);

        // stage 序列覆盖 pipeline 各阶段,且 progress 单调推进
        assertThat(stages).extracting(StageUpdate::stage)
                .contains("planning", "searching", "verifying", "mapping", "writing");
        int prev = -1;
        for (StageUpdate s : stages) {
            assertThat(s.progress()).isGreaterThan(prev);
            prev = s.progress();
        }
        // 各外部组件被调用
        verify(graphExtractor).extract(anyString(), anyString(), anyList());
        verify(reportBuilder).build(anyString(), anyString(), any(JsonNode.class), anyList(), anyString());
    }

    // ── 致命分支1: LLM 未配置 → 503 ──

    @Test
    void researchThrows503WhenLlmNotConfigured() {
        when(chat.available()).thenReturn(false);

        BizException e = assertThrows(BizException.class, () -> orchestrator.research(
                "标题", "说明", List.of(), List.of(), 1L, 10L, 100L, (s, p, m) -> {}));

        assertThat(e.getCode()).isEqualTo(503);
        // 未配置时应在任何联网/agent 工作前就抛出
        verify(webSearch, never()).search(anyString(), any(), anyList(), anyInt());
        verify(graphExtractor, never()).extract(anyString(), anyString(), anyList());
    }

    @Test
    void resumesFromEvidenceCheckpointWithoutRepeatingEarlierStages() {
        stubLlmConfigured();
        List<SearchSource> sources = List.of(src("http://saved.com", "已保存来源"));
        IndustryChainResearchOrchestrator.ResearchCheckpoint checkpoint =
                new IndustryChainResearchOrchestrator.ResearchCheckpoint(
                        "已保存计划", List.of("已保存查询"), sources, "已保存论坛摘要",
                        "已核验证据 [S1]", null, null, null);
        JsonNode graph = graphJson(2, 1);
        when(graphExtractor.extract(anyString(), anyString(), anyList())).thenReturn(graph);
        when(reportBuilder.build(anyString(), anyString(), any(JsonNode.class), anyList(), anyString()))
                .thenReturn(new ResearchReportBuilder.ReportResult("{\"doc\":\"ir\"}", "# 恢复报告"));
        List<StageUpdate> stages = new ArrayList<>();
        List<IndustryChainResearchOrchestrator.ResearchCheckpoint> saved = new ArrayList<>();

        IndustryChainResearchOrchestrator.ResearchResult result = orchestrator.research(
                "标题", "说明", List.of(), List.of(), 1L, 10L, 100L,
                (stage, progress, message) -> stages.add(new StageUpdate(stage, progress, message)),
                checkpoint, saved::add, true);

        assertThat(result.reportMarkdown()).isEqualTo("# 恢复报告");
        assertThat(stages).extracting(StageUpdate::stage)
                .containsExactly("mapping", "writing", "finalizing");
        assertThat(saved).hasSize(2);
        verify(webSearch, never()).search(anyString(), any(), anyList(), anyInt());
        verify(chat, never()).chat(anyString());
        verify(graphExtractor).extract(anyString(), anyString(), anyList());
        verify(reportBuilder).build(anyString(), anyString(), any(JsonNode.class), anyList(), anyString());
    }

    // ── 致命分支2: 所有来源为空 → 502 ──

    @Test
    void researchThrows502WhenNoSourcesObtained() {
        stubLlmConfigured();
        when(chat.chat(anyString())).thenReturn("计划");  // 无「补充查询」段
        when(chatModel.chat(anyString())).thenReturn("总结");
        // 联网搜索全部返回空(模拟 sogou 不可达)
        when(webSearch.search(anyString(), any(), anyList(), anyInt())).thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> orchestrator.research(
                "标题", "说明", List.of(), List.of(),  // 无用户资料 + 无联网 = 合并后空
                1L, 10L, 100L, (s, p, m) -> {}));

        assertThat(e.getCode()).isEqualTo(502);
        // 空来源时不应进入图谱/报告阶段
        verify(graphExtractor, never()).extract(anyString(), anyString(), anyList());
        verify(reportBuilder, never()).build(anyString(), anyString(), any(JsonNode.class), anyList(), anyString());
    }

    // ── 用户资料优先编号 S1..Sk,联网去重接续 ──

    @Test
    void userSourcesNumberedFirstAndWebSourcesDedupedByUrl() {
        stubLlmConfigured();
        when(chat.chat(anyString())).thenReturn("计划");
        when(chatModel.chat(anyString())).thenReturn("总结");
        when(webSearch.search(anyString(), any(), anyList(), anyInt())).thenReturn(List.of(
                src("http://dup.com", "重复来源"),   // 与用户资料 URL 相同 → 去重
                src("http://new.com", "新来源")));
        when(graphExtractor.extract(anyString(), anyString(), anyList())).thenReturn(graphJson(1, 0));
        when(reportBuilder.build(anyString(), anyString(), any(JsonNode.class), anyList(), anyString()))
                .thenReturn(new ResearchReportBuilder.ReportResult("{\"doc\":\"ir\"}", "# 报告"));

        IndustryChainResearchOrchestrator.ResearchResult result = orchestrator.research(
                "标题", "说明", List.of(),
                List.of(src("http://dup.com", "用户资料1"), src("http://u2.com", "用户资料2")),
                1L, 10L, 100L, (s, p, m) -> {});

        // 用户资料 S1,S2 + 联网去重后(dup 被用户资料占的 URL 去掉,只剩 new)S3
        assertThat(result.sources()).extracting(SearchSource::sourceRef)
                .containsExactly("S1", "S2", "S3");
        assertThat(result.sources()).extracting(SearchSource::url)
                .contains("http://dup.com", "http://u2.com", "http://new.com");
    }

    // ── 规划 Agent reply 路径(非调研,对话收窄)──

    @Test
    void replyReturnsDegradedMessageWhenLlmNotConfigured() {
        when(chat.available()).thenReturn(false);

        String reply = orchestrator.reply("标题", "说明", List.of(), "用户消息");

        assertThat(reply).contains("AI 服务尚未配置");
        verify(chat, never()).chatOr(anyString(), anyString());
    }

    @Test
    void replyCallsChatOrWhenLlmConfigured() {
        stubLlmConfigured();
        when(chat.chatOr(anyString(), anyString())).thenReturn("规划 Agent 回复");

        String reply = orchestrator.reply("标题", "说明", List.of(), "用户消息");

        assertThat(reply).isEqualTo("规划 Agent 回复");
        verify(chat).chatOr(anyString(), anyString());
    }

    // ── 辅助 ──

    private void stubLlmConfigured() {
        when(chat.available()).thenReturn(true);
        when(chat.model()).thenReturn(chatModel);
    }

    private SearchSource src(String url, String title) {
        return new SearchSource(null, title, url, "publisher", "snippet " + title);
    }

    private JsonNode graphJson(int nodeCount, int edgeCount) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        for (int i = 0; i < nodeCount; i++) nodes.addObject().put("id", "n" + i);
        ArrayNode edges = root.putArray("edges");
        for (int i = 0; i < edgeCount; i++) edges.addObject().put("from", "n" + i);
        return root;
    }

    private record StageUpdate(String stage, int progress, String message) {
    }
}
