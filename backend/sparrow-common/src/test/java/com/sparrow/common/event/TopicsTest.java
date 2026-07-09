package com.sparrow.common.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicsTest {

    /**
     * Kafka topic 常量被 trade(生产)与 ai/graph(消费)跨模块共用,
     * 拼写必须完全一致,否则事件发到 A topic 消费方监听 B topic,静默丢失。
     */
    @Test
    void orderPaidTopicContract() {
        assertEquals("sparrow.trade.order-paid", Topics.ORDER_PAID);
        assertTrue(Topics.ORDER_PAID.startsWith("sparrow."));
    }

    @Test
    void graphChangedTopicContract() {
        assertEquals("sparrow.graph.changed", Topics.GRAPH_CHANGED);
        assertTrue(Topics.GRAPH_CHANGED.startsWith("sparrow."));
    }

    @Test
    void twoTopicsAreDistinct() {
        assertTrue(!Topics.ORDER_PAID.equals(Topics.GRAPH_CHANGED));
    }
}
