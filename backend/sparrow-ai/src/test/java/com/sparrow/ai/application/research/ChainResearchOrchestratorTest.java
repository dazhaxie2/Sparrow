package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.infrastructure.research.WebSearchClient;
import com.sparrow.ai.infrastructure.research.WebSearchClient.SearchSource;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainResearchOrchestratorTest {

    @Test
    void buildsTraceableGraphAndRepairsInvalidReportReferences() {
        ChatModel model = mock(ChatModel.class);
        WebSearchClient search = mock(WebSearchClient.class);
        when(search.search(anyString(), anyString())).thenReturn(List.of(
                new SearchSource("S1", "权威来源", "https://example.com/source", "example.com", "已核验事实")));
        when(model.chat(anyString())).thenReturn(
                "调研计划",
                "企业甲向企业乙供应部件 [S1]",
                "{\"nodes\":["
                        + "{\"id\":\"n1\",\"name\":\"企业甲\",\"type\":\"上游供应商\",\"summary\":\"供应方\",\"sourceRefs\":[\"S1\"]},"
                        + "{\"id\":\"n2\",\"name\":\"企业乙\",\"type\":\"中游制造\",\"summary\":\"制造方\",\"sourceRefs\":[\"S1\"]}],"
                        + "\"edges\":[{\"from\":\"n1\",\"to\":\"n2\",\"type\":\"供货\",\"product\":\"部件\",\"sourceRefs\":[\"S1\"]}]}",
                "# 初稿\n错误引用 [S9]",
                "# 修复报告\n企业甲向企业乙供应部件 [S1]");

        ChainResearchOrchestrator orchestrator = new ChainResearchOrchestrator(model, new ObjectMapper(), search);
        var result = orchestrator.research("测试产业链", "限定中国市场", List.of(), (stage, progress, message) -> { });

        assertThat(result.nodeCount()).isEqualTo(2);
        assertThat(result.edgeCount()).isEqualTo(1);
        assertThat(result.reportMarkdown()).contains("修复报告", "[S1]", "https://example.com/source");
    }
}
