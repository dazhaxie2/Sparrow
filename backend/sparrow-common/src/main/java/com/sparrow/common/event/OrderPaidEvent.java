package com.sparrow.common.event;

import java.time.Instant;

public record OrderPaidEvent(
        String eventId,
        String orderNo,
        Long userId,
        String productCode,
        int memberDays,
        int amountCent,
        Instant paidAt,
        Instant occurredAt
) {
}
