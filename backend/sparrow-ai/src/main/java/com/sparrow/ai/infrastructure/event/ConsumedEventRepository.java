package com.sparrow.ai.infrastructure.event;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsumedEventRepository {

    private final JdbcTemplate jdbc;

    public ConsumedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
