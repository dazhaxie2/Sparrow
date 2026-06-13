package com.sparrow.graph.infrastructure.event;

import com.sparrow.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class GraphKafkaConfig {

    @Bean
    NewTopic graphChangedTopic() {
        return TopicBuilder.name(Topics.GRAPH_CHANGED)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
