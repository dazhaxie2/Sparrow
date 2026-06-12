package com.sparrow.ai;

import com.sparrow.graph.TechNode;
import com.sparrow.graph.TechNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AiBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBootstrap.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_INTERVAL_MS = 10_000;

    private final AiProperties props;
    private final AiService aiService;
    private final MilvusStore milvus;
    private final TechNodeMapper nodeMapper;

    public AiBootstrap(AiProperties props, AiService aiService, MilvusStore milvus,
                       TechNodeMapper nodeMapper) {
        this.props = props;
        this.aiService = aiService;
        this.milvus = milvus;
        this.nodeMapper = nodeMapper;
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
        List<TechNode> nodes = nodeMapper.selectList(null);
        List<Long> ids = nodes.stream().map(TechNode::getId).toList();
        List<String> texts = nodes.stream()
                .map(n -> n.getName() + "(" + n.getEra() + "," + n.getYearLabel() + ")。"
                        + n.getSummary() + (n.getDetail() == null ? "" : n.getDetail()))
                .toList();

        List<float[]> vectors = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (vectors == null) {
                    vectors = aiService.embed(texts);
                    log.info("已生成 {} 条节点向量, dim={}", vectors.size(), vectors.get(0).length);
                }
                milvus.rebuild(ids, vectors);
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
}
