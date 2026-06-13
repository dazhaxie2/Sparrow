package com.sparrow.ai.infrastructure.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 记录上一次向量化的内容指纹,持久化在 ai 库(sparrow_ai)。
 * 应用重启时若内容未变可跳过 embedding,避免重复消耗 token。
 */
@Repository
public class RagIndexStateRepository {

    private final JdbcTemplate jdbc;

    public RagIndexStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS rag_index_state ("
                + "id INT NOT NULL PRIMARY KEY,"
                + "fingerprint VARCHAR(80) NOT NULL,"
                + "node_count INT NOT NULL,"
                + "chunk_count INT NOT NULL,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public String currentFingerprint() {
        ensureTable();
        return jdbc.query("SELECT fingerprint FROM rag_index_state WHERE id = 1",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    public void save(String fingerprint, int nodeCount, int chunkCount) {
        ensureTable();
        jdbc.update("INSERT INTO rag_index_state (id, fingerprint, node_count, chunk_count) "
                        + "VALUES (1, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE fingerprint = VALUES(fingerprint), "
                        + "node_count = VALUES(node_count), chunk_count = VALUES(chunk_count)",
                fingerprint, nodeCount, chunkCount);
    }
}
