package com.sparrow.ai.application;

import com.sparrow.ai.infrastructure.client.GraphClient;
import com.sparrow.ai.infrastructure.client.GraphViews.IndexableNode;
import com.sparrow.ai.infrastructure.config.AiProperties;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import com.sparrow.ai.infrastructure.rag.RagDocumentRepository;
import com.sparrow.ai.infrastructure.rag.RagDocumentRepository.RagDoc;
import com.sparrow.ai.infrastructure.rag.RagIndexStateRepository;
import com.sparrow.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 重构自 Phase 1 的 AiBootstrap:数据源改 Feign 调 graph 服务,新增 sync() 入口供 M3 Kafka 消费者触发。
 * SHA-256 指纹 + Milvus 卷复用机制原样保留。
 */
@Component
public class RagIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagIndexer.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_INTERVAL_MS = 10_000;
    private static final int CHUNK_SIZE = 500;

    private final AiProperties props;
    private final AiService aiService;
    private final MilvusStore milvus;
    private final GraphClient graphClient;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagIndexStateRepository indexState;

    public RagIndexer(AiProperties props, AiService aiService, MilvusStore milvus,
                      GraphClient graphClient, RagDocumentRepository ragDocumentRepository,
                      RagIndexStateRepository indexState) {
        this.props = props;
        this.aiService = aiService;
        this.milvus = milvus;
        this.graphClient = graphClient;
        this.ragDocumentRepository = ragDocumentRepository;
        this.indexState = indexState;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.llmConfigured()) {
            log.info("未配置 AI_API_KEY,跳过向量同步,AI 问答将使用图谱规则降级模式");
            return;
        }
        Thread t = new Thread(this::syncWithRetry, "milvus-sync");
        t.setDaemon(true);
        t.start();
    }

    /** Kafka GraphChangedListener 与启动线程都从这里入,运行级别要求单线程串行执行 */
    public synchronized void sync() {
        if (!props.llmConfigured()) {
            return;
        }
        syncWithRetry();
    }

    private void syncWithRetry() {
        ApiResponse<List<IndexableNode>> resp;
        try {
            resp = graphClient.indexableNodes();
        } catch (Exception e) {
            log.warn("无法从 graph 服务获取节点清单,跳过本次同步: {}", e.getMessage());
            return;
        }
        List<IndexableNode> nodes = resp == null ? null : resp.data();
        if (nodes == null || nodes.isEmpty()) {
            log.warn("graph 服务返回节点为空,跳过本次同步");
            return;
        }
        List<Long> ids = nodes.stream().map(IndexableNode::id).toList();
        List<String> texts = nodes.stream()
                .map(n -> n.name() + "(" + n.era() + "," + n.yearLabel() + ")。"
                        + n.summary() + (n.detail() == null ? "" : n.detail()))
                .toList();

        List<String> chunkIds = new ArrayList<>();
        List<String> chunkCodes = new ArrayList<>();
        List<String> chunkUrls = new ArrayList<>();
        List<String> chunkTexts = new ArrayList<>();
        List<RagDoc> docs = loadRagDocs();
        for (RagDoc doc : docs) {
            if (doc.code() == null || doc.content() == null || doc.content().isBlank()) {
                continue;
            }
            String name = doc.name() == null ? doc.code() : doc.name();
            List<String> parts = chunk(doc.content(), CHUNK_SIZE);
            for (int i = 0; i < parts.size(); i++) {
                chunkIds.add(doc.code() + "#" + i);
                chunkCodes.add(doc.code());
                chunkUrls.add(doc.url() == null ? "" : doc.url());
                chunkTexts.add("【" + name + "】" + parts.get(i));
            }
        }
        if (!docs.isEmpty()) {
            log.info("rag_document 语料: {} 篇词条切为 {} 块", docs.size(), chunkIds.size());
        }

        String fingerprint = fingerprint(texts, chunkTexts);
        if (fingerprint.equals(indexState.currentFingerprint())
                && milvus.tryLoadExisting(texts.size(), chunkTexts.size())) {
            log.info("RAG 内容指纹未变({}…),跳过 embedding,复用已持久化向量(节点 {} / 语料块 {})",
                    fingerprint.substring(0, 8), texts.size(), chunkTexts.size());
            return;
        }

        List<float[]> vectors = null;
        List<float[]> chunkVectors = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (vectors == null) {
                    vectors = aiService.embed(texts);
                    log.info("已生成 {} 条节点向量, dim={}", vectors.size(), vectors.get(0).length);
                }
                if (chunkVectors == null) {
                    chunkVectors = chunkTexts.isEmpty() ? List.of() : aiService.embed(chunkTexts);
                    if (!chunkVectors.isEmpty()) {
                        log.info("已生成 {} 条语料块向量", chunkVectors.size());
                    }
                }
                milvus.rebuild(ids, vectors);
                milvus.rebuildChunks(chunkIds, chunkCodes, chunkUrls, chunkTexts, chunkVectors);
                indexState.save(fingerprint, texts.size(), chunkTexts.size());
                return;
            } catch (Exception e) {
                log.warn("Milvus 向量同步失败(第 {}/{} 次): {}", attempt, MAX_ATTEMPTS, e.getMessage());
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("Milvus 向量同步最终失败,AI 检索将使用关键词降级模式");
    }

    private static String fingerprint(List<String> nodeTexts, List<String> chunkTexts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String t : nodeTexts) {
                md.update(t.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            md.update((byte) '|');
            for (String t : chunkTexts) {
                md.update(t.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<RagDoc> loadRagDocs() {
        try {
            if (!ragDocumentRepository.tableExists()) {
                log.info("rag_document 表不存在(爬虫尚未同步),仅索引 tech_node");
                return List.of();
            }
            return ragDocumentRepository.listAll();
        } catch (Exception e) {
            log.warn("读取 rag_document 失败,仅索引 tech_node: {}", e.getMessage());
            return List.of();
        }
    }

    static List<String> chunk(String content, int size) {
        String text = content.strip();
        List<String> parts = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + size, text.length());
            if (end < text.length()) {
                int cut = lastSentenceEnd(text, pos + size / 2, end);
                if (cut > 0) {
                    end = cut;
                }
            }
            String part = text.substring(pos, end).strip();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            pos = end;
        }
        return parts;
    }

    private static int lastSentenceEnd(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '!' || c == '?' || c == ';' || c == '\n' || c == '.') {
                return i + 1;
            }
        }
        return -1;
    }
}
