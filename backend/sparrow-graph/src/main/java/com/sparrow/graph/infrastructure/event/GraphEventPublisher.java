package com.sparrow.graph.infrastructure.event;

import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class GraphEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GraphEventPublisher.class);

    private final KafkaTemplate<String, GraphChangedEvent> kafkaTemplate;
    private final boolean enabled;

    public GraphEventPublisher(KafkaTemplate<String, GraphChangedEvent> kafkaTemplate,
                               @Value("${sparrow.events.enabled:false}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
    }

    public void publish(GraphChangedEvent event) {
        if (!enabled) {
            log.debug("Kafka event disabled,skip graph changed event: eventId={}", event.eventId());
            return;
        }
        kafkaTemplate.send(Topics.GRAPH_CHANGED, event.eventId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("图谱变更事件投递失败: eventId={} type={}",
                                event.eventId(), event.changeType(), ex);
                    } else {
                        log.info("图谱变更事件已投递: eventId={} type={}",
                                event.eventId(), event.changeType());
                    }
                });
    }
}
