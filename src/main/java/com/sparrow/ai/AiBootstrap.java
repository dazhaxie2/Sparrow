package com.sparrow.ai;

import com.sparrow.graph.TechNode;
import com.sparrow.graph.TechNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时后台同步节点向量到 Milvus(不阻塞启动,Milvus 慢启动时自动重试)。
 * 未配置 AI_API_KEY 时跳过,AI 问答走规则降级。
 */
@Component
public class AiBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiBootstrap.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_INTERVAL_MS = 10_000;

    private final AiProperties props;
    private final OpenAiClient openAi;
    private final MilvusStore milvus;
    private final TechNodeRepository nodeRepo;

    public AiBootstrap(AiProperties props, OpenAiClient openAi, MilvusStore milvus,
                       TechNodeRepository nodeRepo) {
        this.props = props;
        this.openAi = openAi;
        this.milvus = milvus;
        this.nodeRepo = nodeRepo;
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
        List<TechNode> nodes = nodeRepo.findAll();
        List<Long> ids = nodes.stream().map(TechNode::getId).toList();
        List<String> texts = nodes.stream()
                .map(n -> n.getName() + "(" + n.getEra() + "," + n.getYearLabel() + ")。"
                        + n.getSummary() + (n.getDetail() == null ? "" : n.getDetail()))
                .toList();

        List<float[]> vectors = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (vectors == null) {
                    vectors = openAi.embed(texts);
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
