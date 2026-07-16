package com.sparrow.industrychain.application.graph;

import com.sparrow.common.ai.model.ModelScene;
import com.sparrow.common.ai.Texts;
import com.sparrow.industrychain.application.config.IndustryAgentConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Component
public class ResearchGraphExtractor {

    private final ChatModelProvider chat;
    private final ObjectMapper objectMapper;
    private final ResearchGraphValidator validator;
    private IndustryAgentConfigService agentConfigs;

    public ResearchGraphExtractor(ChatModelProvider chat, ObjectMapper objectMapper,
                                  ResearchGraphValidator validator) {
        this.chat = chat;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Autowired(required = false)
    void setAgentConfigs(IndustryAgentConfigService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    public JsonNode extract(String title, String evidence, List<SearchSource> sources) {
        String configuredPrompt = agentConfigs == null ? "你是产业链关系抽取 Agent。"
                : agentConfigs.requireEnabled(IndustryAgentConfigService.GRAPH_EXTRACTOR).systemPrompt();
        String raw = chat.chat(ModelScene.CHAIN_EXTRACTION, configuredPrompt + "\n\n" + prompt(title, evidence, sources));
        JsonNode graph = parseJson(raw);
        if (!validator.valid(graph, sources)) {
            String repaired = chat.chat(ModelScene.CHAIN_EXTRACTION, "修复下面内容为严格符合原 schema 的 JSON。删除无来源关系，"
                    + "不要添加新事实，只输出 JSON：\n" + Texts.compact(raw, 8000));
            graph = parseJson(repaired);
        }
        if (!validator.valid(graph, sources)) throw new BizException(502, "关系图 Agent 返回的数据无法验证");
        return graph;
    }

    private String prompt(String title, String evidence, List<SearchSource> sources) {
        String refs = sources.stream().map(SearchSource::sourceRef).reduce((a, b) -> a + "," + b).orElse("");
        return """
                你是产业链关系抽取 Agent。只根据核验证据生成关系图，输出严格 JSON，不要 Markdown 围栏。
                节点类型限：核心对象、上游供应商、材料商、设备商、代工厂、中游制造、下游客户、应用市场。
                边方向固定为上游/提供方 -> 下游/接受方。每条边必须有至少一个 sourceRefs，且只能使用：%s。
                没有直接证据的关系不得输出。

                JSON schema：
                {"nodes":[{"id":"n1","name":"名称","type":"类型","summary":"摘要","sourceRefs":["S1"]}],
                "edges":[{"from":"n1","to":"n2","type":"供货|代工|材料供应|设备供应|客户|应用",
                "product":"产品或环节","sourceRefs":["S1"]}]}

                研究对象：%s
                核验证据：%s
                """.formatted(refs, title, Texts.compact(evidence, 7500));
    }

    private JsonNode parseJson(String raw) {
        try {
            int start = raw == null ? -1 : raw.indexOf('{');
            int end = raw == null ? -1 : raw.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            return objectMapper.readTree(raw.substring(start, end + 1));
        } catch (Exception error) {
            return null;
        }
    }
}
