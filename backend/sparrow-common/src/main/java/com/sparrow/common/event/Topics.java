package com.sparrow.common.event;

/**
 * Kafka topic 常量。两端共用,避免拼写漂移。
 */
public final class Topics {

    /** trade 全局事务提交后投递:消费方写会员开通流水/审计 */
    public static final String ORDER_PAID = "sparrow.trade.order-paid";

    /** graph 导入/重建后投递:ai 消费触发 Milvus 重建索引 */
    public static final String GRAPH_CHANGED = "sparrow.graph.changed";

    private Topics() {
    }
}
