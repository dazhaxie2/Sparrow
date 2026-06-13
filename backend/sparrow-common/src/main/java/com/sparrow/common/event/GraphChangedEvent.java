package com.sparrow.common.event;

import java.time.Instant;

public record GraphChangedEvent(
        String eventId,
        String changeType,
        int nodeCount,
        Instant occurredAt
) {
    public static final String TYPE_FULL_IMPORT = "FULL_IMPORT";
    public static final String TYPE_REINDEX = "REINDEX";
}
