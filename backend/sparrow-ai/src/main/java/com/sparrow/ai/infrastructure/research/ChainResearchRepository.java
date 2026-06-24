package com.sparrow.ai.infrastructure.research;

import jakarta.annotation.PostConstruct;
import com.sparrow.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ChainResearchRepository {

    public record CardRow(Long id, Long userId, String title, String brief, String status,
                          String currentStage, int progress, int nodeCount, int edgeCount,
                          String graphJson, String reportMd, String lastError,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record MessageRow(Long id, Long cardId, String role, String agent, String content,
                             LocalDateTime createdAt) {
    }

    public record RunRow(Long id, Long cardId, String status, String currentStage, int progress,
                         String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record SourceRow(Long id, Long cardId, String sourceRef, String title, String url,
                            String publisher, String snippet) {
    }

    public record SourceInput(String sourceRef, String title, String url, String publisher, String snippet) {
    }

    private final JdbcTemplate jdbc;

    public ChainResearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS chain_research_card ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,"
                + "title VARCHAR(120) NOT NULL,brief TEXT NULL,status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',"
                + "current_stage VARCHAR(32) NULL,progress INT NOT NULL DEFAULT 0,"
                + "node_count INT NOT NULL DEFAULT 0,edge_count INT NOT NULL DEFAULT 0,"
                + "graph_json LONGTEXT NULL,report_md LONGTEXT NULL,last_error VARCHAR(1000) NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "KEY idx_chain_research_user_updated(user_id,updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chain_research_message ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "role VARCHAR(16) NOT NULL,agent VARCHAR(32) NULL,content TEXT NOT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "KEY idx_chain_research_message_card(card_id,id),KEY idx_chain_research_message_user(user_id)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chain_research_run ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "status VARCHAR(24) NOT NULL,current_stage VARCHAR(32) NULL,progress INT NOT NULL DEFAULT 0,"
                + "error_message VARCHAR(1000) NULL,started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "finished_at DATETIME NULL,KEY idx_chain_research_run_card(card_id,id),"
                + "KEY idx_chain_research_run_user(user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chain_research_source ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "source_ref VARCHAR(16) NOT NULL,title VARCHAR(300) NOT NULL,url VARCHAR(1200) NOT NULL,"
                + "publisher VARCHAR(160) NULL,snippet VARCHAR(1200) NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uk_chain_research_source_ref(card_id,source_ref),"
                + "KEY idx_chain_research_source_user(user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public long createCard(long userId, String title, String brief) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chain_research_card(user_id,title,brief,status) VALUES(?,?,?,'DRAFT')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, title);
            statement.setString(3, brief);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("未生成调研卡片 ID");
        return key.getKey().longValue();
    }

    public List<CardRow> listCards(long userId) {
        return jdbc.query("SELECT * FROM chain_research_card WHERE user_id=? ORDER BY updated_at DESC,id DESC",
                (rs, n) -> card(rs), userId);
    }

    public Optional<CardRow> findCard(long userId, long cardId) {
        return jdbc.query("SELECT * FROM chain_research_card WHERE id=? AND user_id=?",
                (rs, n) -> card(rs), cardId, userId).stream().findFirst();
    }

    public void updateCard(long userId, long cardId, String title, String brief) {
        jdbc.update("UPDATE chain_research_card SET title=?,brief=? WHERE id=? AND user_id=?",
                title, brief, cardId, userId);
    }

    @Transactional
    public void deleteCard(long userId, long cardId) {
        jdbc.update("DELETE FROM chain_research_source WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM chain_research_message WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM chain_research_run WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM chain_research_card WHERE id=? AND user_id=?", cardId, userId);
    }

    public long addMessage(long userId, long cardId, String role, String agent, String content) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chain_research_message(card_id,user_id,role,agent,content) VALUES(?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, cardId);
            statement.setLong(2, userId);
            statement.setString(3, role);
            statement.setString(4, agent);
            statement.setString(5, content);
            return statement;
        }, key);
        return key.getKey() == null ? 0 : key.getKey().longValue();
    }

    public List<MessageRow> messages(long userId, long cardId) {
        return jdbc.query("SELECT id,card_id,role,agent,content,created_at FROM chain_research_message "
                        + "WHERE card_id=? AND user_id=? ORDER BY id",
                (rs, n) -> new MessageRow(rs.getLong("id"), rs.getLong("card_id"),
                        rs.getString("role"), rs.getString("agent"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), cardId, userId);
    }

    @Transactional
    public long createRun(long userId, long cardId) {
        jdbc.queryForObject("SELECT id FROM chain_research_card WHERE id=? AND user_id=? FOR UPDATE",
                Long.class, cardId, userId);
        Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM chain_research_run "
                + "WHERE card_id=? AND user_id=? AND status='RUNNING'", Integer.class, cardId, userId);
        if (running != null && running > 0) throw new BizException(409, "已有调研任务正在运行");
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chain_research_run(card_id,user_id,status,current_stage,progress) "
                            + "VALUES(?,?,'RUNNING','planning',5)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, cardId);
            statement.setLong(2, userId);
            return statement;
        }, key);
        jdbc.update("UPDATE chain_research_card SET status='RESEARCHING',current_stage='planning',"
                + "progress=5,last_error=NULL WHERE id=? AND user_id=?", cardId, userId);
        if (key.getKey() == null) throw new IllegalStateException("未生成调研任务 ID");
        return key.getKey().longValue();
    }

    public Optional<RunRow> findRun(long userId, long cardId, long runId) {
        return jdbc.query("SELECT * FROM chain_research_run WHERE id=? AND card_id=? AND user_id=?",
                (rs, n) -> run(rs), runId, cardId, userId).stream().findFirst();
    }

    public Optional<RunRow> activeRun(long userId, long cardId) {
        return jdbc.query("SELECT * FROM chain_research_run WHERE card_id=? AND user_id=? "
                        + "AND status='RUNNING' ORDER BY id DESC LIMIT 1",
                (rs, n) -> run(rs), cardId, userId).stream().findFirst();
    }

    public void updateProgress(long userId, long cardId, long runId, String stage, int progress) {
        jdbc.update("UPDATE chain_research_run SET current_stage=?,progress=? WHERE id=? AND user_id=?",
                stage, progress, runId, userId);
        jdbc.update("UPDATE chain_research_card SET current_stage=?,progress=? WHERE id=? AND user_id=?",
                stage, progress, cardId, userId);
    }

    public boolean isRunRunning(long userId, long runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chain_research_run "
                + "WHERE id=? AND user_id=? AND status='RUNNING'", Integer.class, runId, userId);
        return count != null && count > 0;
    }

    public void cancelRun(long userId, long cardId, long runId) {
        jdbc.update("UPDATE chain_research_run SET status='CANCELLED',current_stage='cancelled',"
                        + "finished_at=NOW() WHERE id=? AND card_id=? AND user_id=? AND status='RUNNING'",
                runId, cardId, userId);
        jdbc.update("UPDATE chain_research_card SET status=IF(graph_json IS NULL,'DRAFT','COMPLETED'),"
                        + "current_stage=IF(graph_json IS NULL,'cancelled','completed'),"
                        + "progress=IF(graph_json IS NULL,0,100) WHERE id=? AND user_id=?",
                cardId, userId);
    }

    @Transactional
    public void complete(long userId, long cardId, long runId, String graphJson, String reportMd,
                         int nodeCount, int edgeCount, List<SourceInput> sources) {
        jdbc.update("DELETE FROM chain_research_source WHERE card_id=? AND user_id=?", cardId, userId);
        for (SourceInput source : sources) {
            jdbc.update("INSERT INTO chain_research_source(card_id,user_id,source_ref,title,url,publisher,snippet) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    cardId, userId, source.sourceRef(), source.title(), source.url(),
                    source.publisher(), source.snippet());
        }
        jdbc.update("UPDATE chain_research_run SET status='COMPLETED',current_stage='completed',progress=100,"
                        + "finished_at=NOW(),error_message=NULL WHERE id=? AND user_id=?",
                runId, userId);
        jdbc.update("UPDATE chain_research_card SET status='COMPLETED',current_stage='completed',progress=100,"
                        + "graph_json=?,report_md=?,node_count=?,edge_count=?,last_error=NULL WHERE id=? AND user_id=?",
                graphJson, reportMd, nodeCount, edgeCount, cardId, userId);
    }

    public void fail(long userId, long cardId, long runId, String error) {
        String safe = error == null ? "调研失败" : error.substring(0, Math.min(error.length(), 1000));
        jdbc.update("UPDATE chain_research_run SET status='FAILED',error_message=?,finished_at=NOW() "
                + "WHERE id=? AND user_id=?", safe, runId, userId);
        jdbc.update("UPDATE chain_research_card SET status='FAILED',last_error=? WHERE id=? AND user_id=?",
                safe, cardId, userId);
    }

    public List<SourceRow> sources(long userId, long cardId) {
        return jdbc.query("SELECT id,card_id,source_ref,title,url,publisher,snippet FROM chain_research_source "
                        + "WHERE card_id=? AND user_id=? ORDER BY id",
                (rs, n) -> new SourceRow(rs.getLong("id"), rs.getLong("card_id"),
                        rs.getString("source_ref"), rs.getString("title"), rs.getString("url"),
                        rs.getString("publisher"), rs.getString("snippet")), cardId, userId);
    }

    private CardRow card(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CardRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                rs.getString("brief"), rs.getString("status"), rs.getString("current_stage"),
                rs.getInt("progress"), rs.getInt("node_count"), rs.getInt("edge_count"),
                rs.getString("graph_json"), rs.getString("report_md"), rs.getString("last_error"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private RunRow run(java.sql.ResultSet rs) throws java.sql.SQLException {
        var finished = rs.getTimestamp("finished_at");
        return new RunRow(rs.getLong("id"), rs.getLong("card_id"), rs.getString("status"),
                rs.getString("current_stage"), rs.getInt("progress"), rs.getString("error_message"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                finished == null ? null : finished.toLocalDateTime());
    }
}
