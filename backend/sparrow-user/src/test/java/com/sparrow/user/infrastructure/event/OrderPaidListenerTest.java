package com.sparrow.user.infrastructure.event;

import com.sparrow.common.event.OrderPaidEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaidListenerTest {

    private MemberGrantLogRepository repo;
    private OrderPaidListener listener;

    @BeforeEach
    void setUp() {
        repo = mock(MemberGrantLogRepository.class);
        listener = new OrderPaidListener(repo);
    }

    @Test
    void recordsFirstOrderPaidEvent() {
        when(repo.recordIfAbsent(any())).thenReturn(true);

        listener.onOrderPaid(event("evt-1", "T20260613001"));

        verify(repo, times(1)).recordIfAbsent(any(OrderPaidEvent.class));
    }

    @Test
    void skipsDuplicateOrderPaidEventWithoutThrowing() {
        when(repo.recordIfAbsent(any())).thenReturn(false);

        // 重复事件不应抛异常,只跳过(幂等)
        listener.onOrderPaid(event("evt-1", "T20260613001"));

        verify(repo, times(1)).recordIfAbsent(any(OrderPaidEvent.class));
    }

    private OrderPaidEvent event(String eventId, String orderNo) {
        return new OrderPaidEvent(eventId, orderNo, 42L, "MEMBER_MONTH", 30, 990,
                Instant.now(), Instant.now());
    }
}
