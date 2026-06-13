package com.sparrow.ai.infrastructure.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SparrowSpider 写入的 rag_document 语料表的只读访问。
 * 该表由爬虫自动建表,可能不存在(爬虫未跑过),调用方需先 tableExists() 判表降级。
 */
@Repository
public class RagDocumentRepository {

    public record RagDoc(String code, String name, String title, String url, String content) {
    }

    private final JdbcTemplate jdbc;

    public RagDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name = 'rag_document'",
                Integer.class);
        return count != null && count > 0;
    }

    public List<RagDoc> listAll() {
        return jdbc.query(
                "SELECT code, name, title, url, content FROM rag_document",
                (rs, rowNum) -> new RagDoc(rs.getString("code"), rs.getString("name"),
                        rs.getString("title"), rs.getString("url"), rs.getString("content")));
    }
}
