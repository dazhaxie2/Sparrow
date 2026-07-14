package com.sparrow.industrychain.application.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.application.report.ir.DocumentIr;
import com.sparrow.industrychain.application.report.ir.IrValidator;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchReportBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fallsBackToVerifiedEvidenceWhenModelIrCannotBeParsed() throws Exception {
        ChatModelProvider chat = mock(ChatModelProvider.class);
        com.sparrow.common.ai.model.ModelScene scene = com.sparrow.common.ai.model.ModelScene.CHAIN_REPORT;
        when(chat.available(eq(scene))).thenReturn(true);
        when(chat.chat(eq(scene), anyString())).thenReturn("not-json");
        when(chat.chatOr(eq(scene), anyString(), anyString())).thenAnswer(call -> call.getArgument(2));
        ResearchReportBuilder builder = new ResearchReportBuilder(chat, mapper, new IrValidator());
        SearchSource source = new SearchSource("S1", "来源一", "https://example.test/s1", "测试来源", "摘要");

        ResearchReportBuilder.ReportResult result = builder.build(
                "测试产业链", "已核验证据 [S1]", mapper.createObjectNode(), List.of(source), "");

        DocumentIr ir = mapper.readValue(result.irJson(), DocumentIr.class);
        assertThat(ir.chapters()).hasSize(1);
        assertThat(new IrValidator().validate(ir, java.util.Set.of("S1"))).isEmpty();
        assertThat(result.markdown()).contains("已核验证据", "[S1]", "来源一");
    }
}
