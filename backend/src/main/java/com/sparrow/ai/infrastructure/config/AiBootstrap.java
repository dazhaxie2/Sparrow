package com.sparrow.ai.infrastructure.config;

import com.sparrow.graph.api.TechNodeCatalog;
import com.sparrow.graph.api.TechNodeCatalog.IndexableNode;
import com.sparrow.ai.infrastructure.rag.MilvusStore;
import com.sparrow.ai.infrastructure.rag.RagDocumentRepository;
import com.sparrow.ai.infrastructure.rag.RagDocumentRepository.RagDoc;
import com.sparrow.ai.infrastructure.rag.RagIndexStateRepository;
import com.sparrow.ai.application.AiService;
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

@Component
public class AiBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBootstrap.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_INTERVAL_MS = 10_000;
    /** rag_document.content 的切块目标长度(字符) */
    private static final int CHUNK_SIZE = 500;

    private final AiProperties props;
    private final AiService aiService;
    private final MilvusStore milvus;
    private final TechNodeCatalog techNodeCatalog;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagIndexStateRepository indexState;

    public AiBootstrap(AiProperties props, AiService aiService, MilvusStore milvus,
                       TechNodeCatalog techNodeCatalog, RagDocumentRepository ragDocumentRepository,
                       RagIndexStateRepository indexState) {
        this.props = props;
        this.aiService = aiService;
        this.milvus = milvus;
        this.techNodeCatalog = techNodeCatalog;
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

    private void syncWithRetry() {
        List<IndexableNode> nodes = techNodeCatalog.listIndexableNodes();
        List<Long> ids = nodes.stream().map(IndexableNode::id).toList();
        List<String> texts = nodes.stream()
                .map(n -> n.name() + "(" + n.era() + "," + n.yearLabel() + ")。"
                        + n.summary() + (n.detail() == null ? "" : n.detail()))
                .toList();

        // 爬虫语料表(可能不存在),切块后以 {code}#{chunkNo} 入库
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

        // 内容指纹未变 + Milvus 卷里向量行数对得上 → 跳过 embedding,直接复用(0 token)
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

    /** 对全部待索引文本算 SHA-256;内容(增删改)一旦变化指纹即变 */
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

    /** 按目标长度切块,优先在块内后半段的句界处断开,避免句子被拦腰截断 */
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
