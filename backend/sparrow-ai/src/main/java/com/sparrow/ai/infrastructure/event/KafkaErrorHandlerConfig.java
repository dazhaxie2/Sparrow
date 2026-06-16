package com.sparrow.ai.infrastructure.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 错误处理器配置。
 * 配置消息消费失败时的重试策略和异常处理逻辑。
 */
@Configuration
public class KafkaErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

    /**
     * 创建 Kafka 错误处理器。
     * 配置反序列化异常不重试,其他异常重试一次后丢弃。
     *
     * @return 错误处理器实例
     */
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