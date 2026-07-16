package com.sparrow.industrychain.application.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.application.workflow.IndustryChainResearchOrchestrator.ResearchCheckpoint;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchCheckpointSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripFullCheckpoint() throws Exception {
        ResearchCheckpoint original = new ResearchCheckpoint(
                "规划: 研究锂电池供应链",
                List.of("锂矿 产能", "正极材料 供应商"),
                List.of(
                        new SearchSource("S1", "锂矿开采报告", "https://a.com", "矿业周刊", "全球锂矿产量持续增长"),
                        new SearchSource("S2", "正极材料市场", "https://b.com", "材料在线", "三元材料需求旺盛")
                ),
                "论坛摘要",
                "核验证据: 来源 S1 确认产能数据",
                "{\"nodes\":[{\"id\":\"n1\",\"name\":\"锂矿\"}]}",
                "{\"blocks\":[{\"type\":\"chart\"}]}",
                "## 锂电池供应链研究报告\n\n### 上游\n..."
        );
        String json = mapper.writeValueAsString(original);
        ResearchCheckpoint restored = mapper.readValue(json, ResearchCheckpoint.class);

        assertThat(restored).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void roundTripEmptyCheckpoint() throws Exception {
        ResearchCheckpoint empty = ResearchCheckpoint.empty();
        String json = mapper.writeValueAsString(empty);
        ResearchCheckpoint restored = mapper.readValue(json, ResearchCheckpoint.class);

        assertThat(restored.plan()).isNull();
        assertThat(restored.planQueries()).isEmpty();
        assertThat(restored.sources()).isEmpty();
        assertThat(restored.forumDigest()).isNull();
        assertThat(restored.evidence()).isNull();
        assertThat(restored.graphJson()).isNull();
        assertThat(restored.reportIrJson()).isNull();
        assertThat(restored.reportMarkdown()).isNull();
    }

    @Test
    void roundTripNullFields() throws Exception {
        ResearchCheckpoint partial = new ResearchCheckpoint(
                "仅规划", null, null, null, null, null, null, null
        );
        String json = mapper.writeValueAsString(partial);
        ResearchCheckpoint restored = mapper.readValue(json, ResearchCheckpoint.class);

        assertThat(restored.plan()).isEqualTo("仅规划");
        assertThat(restored.planQueries()).isNull();
        assertThat(restored.sources()).isNull();
    }
}
