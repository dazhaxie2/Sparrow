package com.sparrow.user.infrastructure.event;

import com.sparrow.common.event.OrderPaidEvent;
import com.sparrow.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 trade 的 OrderPaidEvent,记录会员开通流水(审计/对账)。
 * 会员开通本身已在 trade 的 Seata 全局事务中通过 Feign 完成,这里不重复开通,只做幂等审计落库。
 */
@Component
public class OrderPaidListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidListener.class);

    private final MemberGrantLogRepository grantLogRepository;

    public OrderPaidListener(MemberGrantLogRepository grantLogRepository) {
        this.grantLogRepository = grantLogRepository;
    }

    @KafkaListener(
            topics = Topics.ORDER_PAID,
            groupId = "${sparrow.kafka.groups.user:sparrow-user}",
            autoStartup = "${sparrow.events.enabled:false}"
    )
    public void onOrderPaid(OrderPaidEvent event) {
        if (!grantLogRepository.recordIfAbsent(event)) {
            log.info("订单支付事件已记录,跳过重复消息: orderNo={} eventId={}",
                    event.orderNo(), event.eventId());
            return;
        }
        log.info("会员开通流水已记录: orderNo={} userId={} days={} eventId={}",
                event.orderNo(), event.userId(), event.memberDays(), event.eventId());
    }
}
