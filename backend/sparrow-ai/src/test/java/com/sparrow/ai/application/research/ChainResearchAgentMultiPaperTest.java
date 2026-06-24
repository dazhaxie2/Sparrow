package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.infrastructure.research.WebSearchClient;
import com.sparrow.ai.infrastructure.research.WebSearchClient.SearchSource;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多智能体调研链路测试：用一批「大模型分布式训练」论文作为来源，检验
 * ChainResearchOrchestrator 的规划→证据→关系图→报告多 Agent 编排与校验/修复机制，
 * 并覆盖「用户附件 + 联网搜索合并编号」的带资料调研能力。
 */
class ChainResearchAgentMultiPaperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<SearchSource> paperSources() {
        return List.of(
                new SearchSource("S1", "ZeRO: Memory Optimizations Toward Training Trillion Parameter Models",
                        "https://www.arxiv.org/abs/1910.02054", "arxiv.org",
                        "ZeRO 消除数据/模型并行中的内存冗余，400 GPU 上训练超 100B 参数，吞吐 15 Petaflops。"),
                new SearchSource("S2", "Megatron-LM: Training Multi-Billion Parameter Language Models Using Model Parallelism",
                        "https://www.arxiv.org/abs/1909.08053", "arxiv.org",
                        "Megatron-LM 提出层内模型并行，512 GPU 收敛 8.3B 参数模型，达到 15.1 PetaFLOPs。"),
                new SearchSource("S3", "GPipe: Efficient Training of Giant Neural Networks using Pipeline Parallelism",
                        "https://www.arxiv.org/abs/1811.06965", "arxiv.org",
                        "GPipe 使用批切分流水线并行，跨加速器近线性加速，训练 6B 参数多语言翻译模型。"),
                new SearchSource("S4", "FlashAttention: Fast and Memory-Efficient Exact Attention with IO-Awareness",
                        "https://www.arxiv.org/abs/2205.14135", "arxiv.org",
                        "FlashAttention 用分块减少 HBM 读写，GPT-2 上 3 倍加速并支持更长上下文。"));
    }

    /** 端到端：论文作为来源跑完整多 Agent 流程，校验图谱结构与带引用报告。 */
    @Test
    void researchesPapersAsSupplyChainSources() {
        ChatModel model = mock(ChatModel.class);
        WebSearchClient search = mock(WebSearchClient.class);
        when(search.search(anyString(), anyString(), anyList(), anyInt())).thenReturn(paperSources());

        when(model.chat(anyString())).thenReturn(
                "调研计划：覆盖并行策略内存优化、张量/流水线并行、注意力算子优化。",
                "ZeRO 通过消除内存冗余支持超大规模训练 [S1]。"
                        + "Megatron-LM 提出层内模型并行用于训练多十亿参数模型 [S2]。"
                        + "GPipe 采用流水线并行训练超大网络 [S3]。"
                        + "FlashAttention 优化注意力 IO 显存与速度 [S4]。",
                "{\"nodes\":["
                        + "{\"id\":\"n1\",\"name\":\"ZeRO\",\"type\":\"核心对象\",\"summary\":\"内存优化\",\"sourceRefs\":[\"S1\"]},"
                        + "{\"id\":\"n2\",\"name\":\"Megatron-LM\",\"type\":\"中游制造\",\"summary\":\"张量并行\",\"sourceRefs\":[\"S2\"]},"
                        + "{\"id\":\"n3\",\"name\":\"GPipe\",\"type\":\"上游供应商\",\"summary\":\"流水线并行\",\"sourceRefs\":[\"S3\"]},"
                        + "{\"id\":\"n4\",\"name\":\"FlashAttention\",\"type\":\"设备商\",\"summary\":\"注意力算子\",\"sourceRefs\":[\"S4\"]}"
                        + "],\"edges\":["
                        + "{\"from\":\"n3\",\"to\":\"n2\",\"type\":\"供货\",\"product\":\"流水线方案\",\"sourceRefs\":[\"S3\"]},"
                        + "{\"from\":\"n1\",\"to\":\"n2\",\"type\":\"材料供应\",\"product\":\"内存优化\",\"sourceRefs\":[\"S1\"]}"
                        + "]}",
                "# 分布式训练产业链\nZeRO 支持超大规模训练 [S1]，Megatron-LM 提出张量并行 [S2]。",
                "# 分布式训练产业链\nZeRO 支持超大规模训练 [S1]。");

        ChainResearchOrchestrator orchestrator = new ChainResearchOrchestrator(model, MAPPER, search);
        var result = orchestrator.research(
                "大模型分布式训练系统", "调研并行策略与内存优化",
                List.of(), List.of(), (stage, progress, message) -> { });

        assertThat(result.nodeCount()).isEqualTo(4);
        assertThat(result.edgeCount()).isEqualTo(2);
        assertThat(result.graphJson()).contains("ZeRO", "Megatron-LM", "GPipe", "FlashAttention");
        assertThat(result.reportMarkdown()).contains("[S1]", "[S2]");
        assertThat(result.reportMarkdown()).contains("https://www.arxiv.org/abs/1910.02054");
        assertThat(result.sources()).hasSize(4);
    }

    /**
     * 带资料调研核心场景：用户附件（论文）优先编号 S1，联网来源接续 S2，
     * 校验合并编号后图谱/报告均能正确追溯用户附件编号。
     */
    @Test
    void mergesUserAttachmentsWithWebSearchNumbering() {
        ChatModel model = mock(ChatModel.class);
        WebSearchClient search = mock(WebSearchClient.class);
        // 用户附件 1 条（论文），联网搜索返回 1 条，共 2 条
        SearchSource userPaper = new SearchSource("S1", "ZeRO 论文",
                "https://www.arxiv.org/abs/1910.02054", "arxiv.org",
                "ZeRO 消除内存冗余支持超大规模训练。");
        SearchSource webSource = new SearchSource("S2", "Megatron-LM 行业分析",
                "https://example.com/megatron", "example.com",
                "Megatron-LM 是主流张量并行框架。");
        when(search.search(anyString(), anyString(), anyList(), anyInt())).thenReturn(List.of(webSource));

        when(model.chat(anyString())).thenReturn(
                // 规划：含「补充查询：」段，验证查询词抽取
                "围绕用户提供的 ZeRO 论文展开。\n补充查询：\n最新 GPU 市场\n训练框架对比",
                // 证据：引用了用户附件 S1 和联网 S2
                "ZeRO 消除内存冗余 [S1]。Megatron-LM 是主流张量并行框架 [S2]。",
                // 关系图：节点/边引用 S1 与 S2
                "{\"nodes\":["
                        + "{\"id\":\"n1\",\"name\":\"ZeRO\",\"type\":\"核心对象\",\"summary\":\"内存优化\",\"sourceRefs\":[\"S1\"]},"
                        + "{\"id\":\"n2\",\"name\":\"Megatron-LM\",\"type\":\"中游制造\",\"summary\":\"张量并行\",\"sourceRefs\":[\"S2\"]}"
                        + "],\"edges\":["
                        + "{\"from\":\"n1\",\"to\":\"n2\",\"type\":\"材料供应\",\"product\":\"内存优化\",\"sourceRefs\":[\"S1\",\"S2\"]}"
                        + "]}",
                "# 报告\nZeRO 消除内存冗余 [S1]，搭配 Megatron-LM [S2]。",
                "# 报告\nZeRO 消除内存冗余 [S1]，搭配 Megatron-LM [S2]。");

        ChainResearchOrchestrator orchestrator = new ChainResearchOrchestrator(model, MAPPER, search);
        var result = orchestrator.research("大模型训练", "内存优化", List.of(),
                List.of(userPaper), (s, p, m) -> { });

        // 合并后来源共 2 条，编号连续 S1(用户)、S2(联网)
        assertThat(result.sources()).hasSize(2);
        assertThat(result.sources().get(0).sourceRef()).isEqualTo("S1");
        assertThat(result.sources().get(0).url()).contains("1910.02054");
        assertThat(result.sources().get(1).sourceRef()).isEqualTo("S2");
        // 图谱节点 2、边 1，且报告可追溯用户附件编号
        assertThat(result.nodeCount()).isEqualTo(2);
        assertThat(result.edgeCount()).isEqualTo(1);
        assertThat(result.reportMarkdown()).contains("[S1]", "[S2]", "1910.02054");
    }

    /** 关系图 Agent 返回不存在来源编号 → 校验失败 → 自动修复一轮 → 通过。 */
    @Test
    void repairsGraphWithInvalidSourceRefs() {
        ChatModel model = mock(ChatModel.class);
        WebSearchClient search = mock(WebSearchClient.class);
        when(search.search(anyString(), anyString(), anyList(), anyInt())).thenReturn(paperSources());

        when(model.chat(anyString())).thenReturn(
                "计划",
                "ZeRO 消除内存冗余 [S1]。",
                "{\"nodes\":[{\"id\":\"n1\",\"name\":\"ZeRO\",\"type\":\"核心对象\","
                        + "\"summary\":\"内存优化\",\"sourceRefs\":[\"S9\"]}],\"edges\":[]}",
                "{\"nodes\":[{\"id\":\"n1\",\"name\":\"ZeRO\",\"type\":\"核心对象\","
                        + "\"summary\":\"内存优化\",\"sourceRefs\":[\"S1\"]}],\"edges\":[]}",
                "# 报告\nZeRO 消除内存冗余 [S1]。",
                "# 报告\nZeRO 消除内存冗余 [S1]。");

        ChainResearchOrchestrator orchestrator = new ChainResearchOrchestrator(model, MAPPER, search);
        var result = orchestrator.research("分布式训练", "内存优化", List.of(), List.of(), (s, p, m) -> { });

        assertThat(result.nodeCount()).isEqualTo(1);
        assertThat(result.edgeCount()).isEqualTo(0);
        assertThat(result.graphJson()).contains("\"S1\"").doesNotContain("\"S9\"");
    }

    /** 报告 Agent 初稿无合法引用 → 触发自动修复 → 仍无合法引用 → 抛 BizException(502)。 */
    @Test
    void rejectsReportWithoutValidReferences() {
        ChatModel model = mock(ChatModel.class);
        WebSearchClient search = mock(WebSearchClient.class);
        when(search.search(anyString(), anyString(), anyList(), anyInt())).thenReturn(paperSources());

        when(model.chat(anyString())).thenReturn(
                "计划",
                "ZeRO 消除内存冗余 [S1]。",
                "{\"nodes\":[{\"id\":\"n1\",\"name\":\"ZeRO\",\"type\":\"核心对象\","
                        + "\"summary\":\"内存优化\",\"sourceRefs\":[\"S1\"]}],\"edges\":[]}",
                "# 报告\n这是一个没有任何引用的报告。",
                "# 报告\n修复后依然没有引用。");

        ChainResearchOrchestrator orchestrator = new ChainResearchOrchestrator(model, MAPPER, search);
        assertThatThrownBy(() -> orchestrator.research("分布式训练", "内存优化", List.of(), List.of(), (s, p, m) -> { }))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("来源引用");
    }

    /** 用户附件 + 联网搜索均为空 → 抛 BizException（不退化成模型常识）。 */
    @Test
    void failsWhenAllSourcesEmpty() {
        ChatModel model = mock(ChatModel.class);
        WebSearchClient search = mock(WebSearchClient.class);
        when(search.search(anyString(), anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(model.chat(anyString())).thenReturn("计划");

        ChainResearchOrchestrator orchestrator = new ChainResearchOrchestrator(model, MAPPER, search);
        assertThatThrownBy(() -> orchestrator.research("分布式训练", "内存优化", List.of(), List.of(), (s, p, m) -> { }))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("来源");
    }
}
