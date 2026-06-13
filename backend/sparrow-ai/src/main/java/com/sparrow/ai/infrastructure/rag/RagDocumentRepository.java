package com.sparrow.ai.infrastructure.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SparrowSpider 写入的 rag_document 语料表的只读访问。
 * Phase 2 拆库后:rag_document 留在 staging 库(sparrow),通过 sparrow.rag_document 跨 schema 查询;
 * sparrow.* 库名由 sparrow.ai.staging-schema 注入(默认 sparrow)。
 */
@Repository
public class RagDocumentRepository {

    public record RagDoc(String code, String name, String title, String url, String content) {
    }

    private final JdbcTemplate jdbc;
    private final String stagingSchema;

    public RagDocumentRepository(JdbcTemplate jdbc,
                                 @Value("${sparrow.ai.staging-schema:sparrow}") String stagingSchema) {
        this.jdbc = jdbc;
        this.stagingSchema = stagingSchema;
    }

    public boolean tableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = ? AND table_name = 'rag_document'",
                Integer.class, stagingSchema);
        return count != null && count > 0;
    }

    public List<RagDoc> listAll() {
        return jdbc.query(
                "SELECT code, name, title, url, content FROM " + stagingSchema + ".rag_document",
                (rs, rowNum) -> new RagDoc(rs.getString("code"), rs.getString("name"),
                        rs.getString("title"), rs.getString("url"), rs.getString("content")));
    }
}
