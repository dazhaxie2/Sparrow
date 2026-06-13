package com.sparrow.trade.infrastructure.event;

import com.sparrow.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class TradeKafkaConfig {

    @Bean
    NewTopic orderPaidTopic() {
        return TopicBuilder.name(Topics.ORDER_PAID)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
