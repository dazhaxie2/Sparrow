package com.sparrow.trade.infrastructure.event;

import com.sparrow.common.event.OrderPaidEvent;
import com.sparrow.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OrderPaidEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidEventPublisher.class);

    private final KafkaTemplate<String, OrderPaidEvent> kafkaTemplate;
    private final boolean enabled;

    public OrderPaidEventPublisher(KafkaTemplate<String, OrderPaidEvent> kafkaTemplate,
                                   @Value("${sparrow.events.enabled:false}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
    }

    public void publishAfterCommit(OrderPaidEvent event) {
        if (!enabled) {
            log.debug("Kafka event disabled,skip order paid event: orderNo={}", event.orderNo());
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    private void publish(OrderPaidEvent event) {
        kafkaTemplate.send(Topics.ORDER_PAID, event.orderNo(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("订单支付事件投递失败: orderNo={} eventId={}",
                                event.orderNo(), event.eventId(), ex);
                    } else {
                        log.info("订单支付事件已投递: orderNo={} eventId={}",
                                event.orderNo(), event.eventId());
                    }
                });
    }
}
