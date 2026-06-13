package com.sparrow.user.infrastructure.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, ex) -> log.warn("丢弃异常 Kafka 消息: topic={} partition={} offset={} cause={}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage()),
                new FixedBackOff(500L, 1L));
        handler.addNotRetryableExceptions(DeserializationException.class);
        return handler;
    }
}
