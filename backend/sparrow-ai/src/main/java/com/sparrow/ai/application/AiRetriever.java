package com.sparrow.ai.application;

import com.sparrow.ai.application.AiService.SourceRef;
import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.NodeBrief;
import com.sparrow.ai.infrastructure.client.GraphViews.Tree;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一句话：检索器——向量检索优先,失败降级关键词匹配;embed 批量嵌入。 */
class AiRetriever {

    private static final Logger log = LoggerFactory.getLogger(AiRetriever.class);

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final MilvusStore milvus;
    private final GraphClient graphClient;

    AiRetriever(ChatModel chatModel, EmbeddingModel embeddingModel, MilvusStore milvus, GraphClient graphClient) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.milvus = milvus;
        this.graphClient = graphClient;
    }

    /** 检索结果:匹配节点 + 匹配语料块。 */
    record Retrieved(List<NodeBrief> nodes, List<MilvusStore.ChunkHit> chunks) {
    }

    /** 向量检索优先,失败/未配置降级为关键词匹配。 */
    Retrieved retrieve(String question, int configuredLimit) {
        int limit = Math.max(1, Math.min(configuredLimit, 20));
        if (llmConfigured() && milvus.ready()) {
            try {
                float[] vec = embed(List.of(question)).get(0);
                List<Long> ids = milvus.search(vec, limit);
                List<MilvusStore.ChunkHit> chunks = milvus.searchChunks(vec, limit);

                Map<Long, NodeBrief> byId = nodesById();
                List<NodeBrief> nodes = new ArrayList<>(
                        ids.stream().map(byId::get).filter(n -> n != null).toList());
                Map<String, NodeBrief> byCode = new LinkedHashMap<>();
                byId.values().forEach(n -> byCode.put(n.code(), n));
                for (MilvusStore.ChunkHit c : chunks) {
                    NodeBrief n = byCode.get(c.code());
                    if (n != null && nodes.stream().noneMatch(x -> x.id().equals(n.id()))) {
                        nodes.add(n);
                    }
                }
                return new Retrieved(nodes, chunks);
            } catch (Exception e) {
                log.warn("向量检索失败,降级为关键词匹配: {}", AiHarness.safeFailure(e));
            }
        }
        return new Retrieved(keywordMatch(question), List.of());
    }

    /** 构建来源引用列表(节点 id/name + 语料块 url)。 */
    List<SourceRef> buildSources(Retrieved retrieved) {
        Map<String, String> urlByCode = new LinkedHashMap<>();
        for (MilvusStore.ChunkHit c : retrieved.chunks()) {
            if (c.url() != null && !c.url().isBlank()) {
                urlByCode.putIfAbsent(c.code(), c.url());
            }
        }
        return retrieved.nodes().stream()
                .map(n -> new SourceRef(n.id(), n.name(), urlByCode.get(n.code())))
                .toList();
    }

    /** 批量嵌入(每批 10 条),embeddingModel 未配置抛 503。 */
    List<float[]> embed(List<String> texts) {
        if (embeddingModel == null) {
            throw new BizException(503, "AI 服务未配置");
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += 10) {
            List<String> batch = texts.subList(i, Math.min(i + 10, texts.size()));
            List<TextSegment> segments = batch.stream().map(TextSegment::from).toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            for (Embedding emb : embeddings) {
                result.add(emb.vector());
            }
        }
        return result;
    }

    private boolean llmConfigured() {
        return chatModel != null && embeddingModel != null;
    }

    private Map<Long, NodeBrief> nodesById() {
        Map<Long, NodeBrief> map = new LinkedHashMap<>();
        Tree tree = graphClient.tree().data();
        if (tree != null) {
            tree.nodes().forEach(n -> map.put(n.id(), n));
        }
        return map;
    }

    /** 关键词匹配:从节点名提取关键词,按名称长度倒序,最多 3 个。 */
    private List<NodeBrief> keywordMatch(String question) {
        Tree tree = graphClient.tree().data();
        if (tree == null) {
            return List.of();
        }
        List<NodeBrief> matched = new ArrayList<>();
        for (NodeBrief n : tree.nodes()) {
            String key = n.name().split("[((]")[0];
            if (key.length() >= 2 && question.contains(key)) {
                matched.add(n);
            }
        }
        matched.sort(Comparator.comparingInt((NodeBrief n) -> n.name().length()).reversed());
        return matched.size() > 3 ? matched.subList(0, 3) : matched;
    }
}
