package com.sparrow.ai.infrastructure.event;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Kafka 事件消费记录仓储。
 * 用于记录已处理的事件,防止重复消费。
 */
@Repository
public class ConsumedEventRepository {

    private final JdbcTemplate jdbc;

    /**
     * 构造函数。
     *
     * @param jdbc JDBC 模板
     */
    public ConsumedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 标记事件为已消费。
     * 如果事件已被消费则返回 false。
     *
     * @param consumer 消费者标识
     * @param eventId  事件 ID
     * @param topic    主题名称
     * @return true 表示成功标记,false 表示事件已被消费
     */
    public boolean markIfNew(String consumer, String eventId, String topic) {
        ensureTable();
        try {
            jdbc.update("INSERT INTO kafka_consumed_event (consumer, event_id, topic) VALUES (?, ?, ?)",
                    consumer, eventId, topic);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 确保消费记录表存在。
     */
    private void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS kafka_consumed_event ("
                + "consumer VARCHAR(64) NOT NULL,"
                + "event_id VARCHAR(128) NOT NULL,"
                + "topic VARCHAR(128) NOT NULL,"
                + "consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (consumer, event_id),"
                + "KEY idx_topic_consumed_at (topic, consumed_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}