package com.sparrow.ai.infrastructure.event;

import com.sparrow.ai.application.RagIndexer;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class GraphChangedListener {

    private static final Logger log = LoggerFactory.getLogger(GraphChangedListener.class);

    private final RagIndexer ragIndexer;

    public GraphChangedListener(RagIndexer ragIndexer) {
        this.ragIndexer = ragIndexer;
    }

    @KafkaListener(
            topics = Topics.GRAPH_CHANGED,
            groupId = "${sparrow.kafka.groups.ai:sparrow-ai}",
            autoStartup = "${sparrow.events.enabled:false}"
    )
    public void onGraphChanged(GraphChangedEvent event) {
        log.info("收到图谱变更事件,触发 RAG 同步: eventId={} type={} nodes={}",
                event.eventId(), event.changeType(), event.nodeCount());
        ragIndexer.sync();
    }
}
