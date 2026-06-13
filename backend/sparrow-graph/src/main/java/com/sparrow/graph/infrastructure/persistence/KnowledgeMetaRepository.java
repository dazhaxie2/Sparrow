package com.sparrow.graph.infrastructure.persistence;

import com.sparrow.graph.interfaces.dto.GraphDtos.KnowledgeStatus;
import com.sparrow.graph.interfaces.dto.GraphDtos.SourceBrief;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.regex.Pattern;

@Repository
public class KnowledgeMetaRepository {

    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate jdbc;
    private final String ragSchema;
    private final String aiSchema;

    public KnowledgeMetaRepository(JdbcTemplate jdbc,
                                   @Value("${sparrow.graph.rag-schema:sparrow}") String ragSchema,
                                   @Value("${sparrow.graph.ai-schema:sparrow_ai}") String aiSchema) {
        this.jdbc = jdbc;
        this.ragSchema = safeSchema(ragSchema);
        this.aiSchema = safeSchema(aiSchema);
    }

    public List<SourceBrief> sourcesForCode(String code) {
        if (!tableExists(ragSchema, "rag_document")) {
            return List.of();
        }
        return jdbc.query(
                "SELECT title, url, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at"
                        + " FROM " + ragSchema + ".rag_document WHERE code = ? LIMIT 3",
                (rs, rowNum) -> new SourceBrief(
                        rs.getString("title"),
                        rs.getString("url"),
                        rs.getString("updated_at")
                ),
                code
        );
    }

    public KnowledgeStatus status() {
        long docs = 0;
        String ragUpdatedAt = null;
        if (tableExists(ragSchema, "rag_document")) {
            Meta meta = jdbc.queryForObject(
                    "SELECT COUNT(*) AS n, DATE_FORMAT(MAX(updated_at), '%Y-%m-%d %H:%i:%s') AS updated_at"
                            + " FROM " + ragSchema + ".rag_document",
                    (rs, rowNum) -> new Meta(rs.getLong("n"), rs.getString("updated_at"))
            );
            if (meta != null) {
                docs = meta.count();
                ragUpdatedAt = meta.updatedAt();
            }
        }

        boolean indexed = false;
        Integer nodeCount = null;
        Integer chunkCount = null;
        String indexUpdatedAt = null;
        if (tableExists(aiSchema, "rag_index_state")) {
            List<IndexState> states = jdbc.query(
                    "SELECT node_count, chunk_count, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at"
                            + " FROM " + aiSchema + ".rag_index_state WHERE id = 1 LIMIT 1",
                    (rs, rowNum) -> new IndexState(
                            rs.getInt("node_count"),
                            rs.getInt("chunk_count"),
                            rs.getString("updated_at")
                    )
            );
            if (!states.isEmpty()) {
                IndexState state = states.get(0);
                indexed = true;
                nodeCount = state.nodeCount();
                chunkCount = state.chunkCount();
                indexUpdatedAt = state.updatedAt();
            }
        }

        return new KnowledgeStatus(docs, ragUpdatedAt, indexed, nodeCount, chunkCount, indexUpdatedAt);
    }

    private boolean tableExists(String schema, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, schema, table);
        return count != null && count > 0;
    }

    private static String safeSchema(String schema) {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Unsafe schema name: " + schema);
        }
        return schema;
    }

    private record Meta(long count, String updatedAt) {
    }

    private record IndexState(int nodeCount, int chunkCount, String updatedAt) {
    }
}
