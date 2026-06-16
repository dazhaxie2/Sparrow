package com.sparrow.ai.infrastructure.event;

import com.sparrow.ai.application.RagIndexer;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.event.Topics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图谱变更事件监听器。
 * 监听 GRAPH_CHANGED 事件,触发 RAG 向量索引同步。
 */
@Component
public class GraphChangedListener {

    private static final Logger log = LoggerFactory.getLogger(GraphChangedListener.class);
    private static final String CONSUMER = "ai.graph-reindex";

    private final RagIndexer ragIndexer;
    private final ConsumedEventRepository consumedEventRepository;
    private final ExecutorService reindexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-reindex");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造函数。
     *
     * @param ragIndexer               RAG 索引器
     * @param consumedEventRepository 消费事件仓库(用于幂等)
     */
    public GraphChangedListener(RagIndexer ragIndexer, ConsumedEventRepository consumedEventRepository) {
        this.ragIndexer = ragIndexer;
        this.consumedEventRepository = consumedEventRepository;
    }

    /**
     * 处理图谱变更事件。
     * 检查事件是否已处理,避免重复消费,然后异步触发 RAG 索引同步。
     *
     * @param event 图谱变更事件
     */
    @KafkaListener(
            topics = Topics.GRAPH_CHANGED,
            groupId = "${sparrow.kafka.groups.ai:sparrow-ai}",
            autoStartup = "${sparrow.events.enabled:false}"
    )
    public void onGraphChanged(GraphChangedEvent event) {
        if (!consumedEventRepository.markIfNew(CONSUMER, event.eventId(), Topics.GRAPH_CHANGED)) {
            log.info("图谱变更事件已消费,跳过重复消息: eventId={}", event.eventId());
            return;
        }
        log.info("收到图谱变更事件,触发 RAG 同步: eventId={} type={} nodes={}",
                event.eventId(), event.changeType(), event.nodeCount());
        reindexExecutor.submit(() -> {
            try {
                ragIndexer.sync();
            } catch (Exception e) {
                log.warn("RAG async sync failed after graph event: eventId={} err={}",
                        event.eventId(), e.getMessage());
            }
        });
    }

    /**
     * 应用关闭时清理线程池。
     */
    @PreDestroy
    void shutdown() {
        reindexExecutor.shutdownNow();
    }
}