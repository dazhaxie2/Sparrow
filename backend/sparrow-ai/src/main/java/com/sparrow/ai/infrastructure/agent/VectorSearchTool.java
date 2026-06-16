package com.sparrow.ai.infrastructure.agent;

import com.sparrow.ai.application.AiService;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量知识库搜索工具。
 * 供 TechTreeAgent 调用,用于在科技知识库中搜索相关技术节点和词条。
 */
@Component
public class VectorSearchTool {

    private final AiService aiService;
    private final MilvusStore milvus;

    /**
     * 构造函数。
     *
     * @param aiService AI 服务(提供向量化能力)
     * @param milvus    Milvus 向量存储
     */
    public VectorSearchTool(AiService aiService, MilvusStore milvus) {
        this.aiService = aiService;
        this.milvus = milvus;
    }

    @Tool("在科技知识库中搜索与问题最相关的技术节点和词条,返回相关摘要")
    public String searchKnowledge(
            @P("搜索问题或关键词") String query) {
        if (!aiService.llmConfigured() || !milvus.ready()) {
            return "知识库检索功能暂不可用";
        }
        try {
            float[] vec = aiService.embed(List.of(query)).get(0);
            List<Long> ids = milvus.search(vec, 5);
            List<MilvusStore.ChunkHit> chunks = milvus.searchChunks(vec, 4);
            StringBuilder sb = new StringBuilder();
            if (!ids.isEmpty()) {
                sb.append("相关节点ID: ").append(ids.stream().map(String::valueOf)
                        .collect(Collectors.joining(", "))).append("\n");
            }
            if (!chunks.isEmpty()) {
                sb.append("相关词条:\n");
                for (MilvusStore.ChunkHit c : chunks) {
                    sb.append("- ").append(c.text(), 0, Math.min(c.text().length(), 200)).append("\n");
                }
            }
            return sb.isEmpty() ? "未找到相关知识" : sb.toString();
        } catch (Exception e) {
            return "知识检索失败: " + e.getMessage();
        }
    }
}
