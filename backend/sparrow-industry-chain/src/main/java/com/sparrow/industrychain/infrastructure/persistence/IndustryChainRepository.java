package com.sparrow.industrychain.infrastructure.persistence;

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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class IndustryChainRepository {

    /** 调研卡片行数据：基本信息、状态、图谱与报告内容。 */
    public record CardRow(Long id, Long userId, String title, String brief, String status,
                          String currentStage, int progress, int nodeCount, int edgeCount,
                          String graphJson, String reportIr, String reportMd, String lastError,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 对话消息行数据：角色、发送方、内容。 */
    public record MessageRow(Long id, Long cardId, String role, String agent, String content,
                             LocalDateTime createdAt) {
    }

    /** 一次原子问答轮次生成的消息 ID。 */
    public record MessageIds(long userMessageId, long assistantMessageId) {
    }

    /** 运行任务行数据：状态、进度、错误信息与时间戳。 */
    public record RunRow(Long id, Long cardId, String status, String currentStage, int progress,
                         String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 来源行数据：调研结果中引用的来源记录。 */
    public record SourceRow(Long id, Long cardId, String sourceRef, String title, String url,
                            String publisher, String snippet) {
    }

    /** 来源输入：用于持久化来源/附件的数据结构。 */
    public record SourceInput(String sourceRef, String title, String url, String publisher, String snippet) {
    }

    /** 用户上传/附带的资料来源：随卡片持久化，调研时作为优先编号的来源参与证据核验。 */
    public record AttachmentRow(Long id, Long cardId, String sourceRef, String title, String url,
                                String publisher, String snippet) {
    }

    /** 论坛事件行：Multi-Agent 协作过程中的一条发言/系统记录。 */
    public record ForumEventRow(Long id, Long cardId, Long runId, Long userId, String source,
                                String content, LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;

    public IndustryChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化建表：启动时确保所有调研相关表存在，支持老库平滑升级。 */
    @PostConstruct
    public void ensureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS research_card ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,"
                + "title VARCHAR(120) NOT NULL,brief TEXT NULL,status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',"
                + "current_stage VARCHAR(32) NULL,progress INT NOT NULL DEFAULT 0,"
                + "node_count INT NOT NULL DEFAULT 0,edge_count INT NOT NULL DEFAULT 0,"
                + "graph_json LONGTEXT NULL,report_md LONGTEXT NULL,last_error VARCHAR(1000) NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "KEY idx_research_user_updated(user_id,updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS research_message ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "role VARCHAR(16) NOT NULL,agent VARCHAR(32) NULL,content TEXT NOT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "KEY idx_research_message_card(card_id,id),KEY idx_research_message_user(user_id)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS research_run ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "status VARCHAR(24) NOT NULL,current_stage VARCHAR(32) NULL,progress INT NOT NULL DEFAULT 0,"
                + "error_message VARCHAR(1000) NULL,checkpoint_json LONGTEXT NULL,"
                + "started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "finished_at DATETIME NULL,KEY idx_research_run_card(card_id,id),"
                + "KEY idx_research_run_user(user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS research_source ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "source_ref VARCHAR(16) NOT NULL,title VARCHAR(300) NOT NULL,url VARCHAR(1200) NOT NULL,"
                + "publisher VARCHAR(160) NULL,snippet VARCHAR(1200) NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uk_research_source_ref(card_id,source_ref),"
                + "KEY idx_research_source_user(user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS research_attachment ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "source_ref VARCHAR(16) NOT NULL,title VARCHAR(300) NOT NULL,url VARCHAR(1200) NOT NULL,"
                + "publisher VARCHAR(160) NULL,snippet VARCHAR(3000) NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uk_research_attachment_ref(card_id,source_ref),"
                + "KEY idx_research_attachment_card(card_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        // Multi-Agent 论坛协作事件流
        jdbc.execute("CREATE TABLE IF NOT EXISTS research_forum ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,card_id BIGINT NOT NULL,user_id BIGINT NOT NULL,"
                + "run_id BIGINT NOT NULL,source VARCHAR(16) NOT NULL,content TEXT NOT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "KEY idx_research_forum_run(card_id,run_id,id),"
                + "KEY idx_research_forum_user(user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        // 平滑升级：老库补 report_ir 列
        addColumnIfMissing("research_card", "report_ir", "LONGTEXT NULL AFTER report_md");
        addColumnIfMissing("research_run", "checkpoint_json", "LONGTEXT NULL AFTER error_message");
        // 模型配置管理：支持网页热切换 LLM 配置,无需重启
        jdbc.execute("CREATE TABLE IF NOT EXISTS model_config ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "name VARCHAR(64) NOT NULL,"
                + "base_url VARCHAR(255) NOT NULL,"
                + "model_name VARCHAR(128) NOT NULL,"
                + "api_key_encrypted VARCHAR(700) NOT NULL,"
                + "max_tokens INT NOT NULL DEFAULT 3000,"
                + "timeout_seconds INT NOT NULL DEFAULT 180,"
                + "max_retries INT NOT NULL DEFAULT 2,"
                + "active TINYINT NOT NULL DEFAULT 0,"
                + "created_by BIGINT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS model_config_audit ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "config_id BIGINT NULL,"
                + "config_name VARCHAR(64) NULL,"
                + "operator_id BIGINT NULL,"
                + "action VARCHAR(16) NOT NULL,"
                + "summary VARCHAR(1000) NULL,"
                + "test_ok TINYINT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "KEY idx_model_config_audit_config(config_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** 幂等加列：列已存在时忽略异常。 */
    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (Exception ignored) {
            // 列已存在(重复升级)或语法不兼容，安全忽略
        }
    }

    /** 创建调研卡片，初始状态为 DRAFT。 */
    public long createCard(long userId, String title, String brief) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO research_card(user_id,title,brief,status) VALUES(?,?,?,'DRAFT')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, title);
            statement.setString(3, brief);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("未生成调研卡片 ID");
        return key.getKey().longValue();
    }

    /** 覆盖式写入卡片附件：先删旧再批量插入，保证调用方传入的列表即最终态。 */
    @Transactional
    public void replaceAttachments(long userId, long cardId, List<SourceInput> attachments) {
        jdbc.update("DELETE FROM research_attachment WHERE card_id=? AND user_id=?", cardId, userId);
        if (attachments == null) return;
        for (SourceInput attachment : attachments) {
            jdbc.update("INSERT INTO research_attachment(card_id,user_id,source_ref,title,url,publisher,snippet) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    cardId, userId, attachment.sourceRef(), attachment.title(), attachment.url(),
                    attachment.publisher(), attachment.snippet());
        }
    }

    /** 追加单个附件（PDF 上传后使用），由调用方分配 sourceRef。 */
    public void addAttachment(long userId, long cardId, SourceInput attachment) {
        jdbc.update("INSERT INTO research_attachment(card_id,user_id,source_ref,title,url,publisher,snippet) "
                        + "VALUES(?,?,?,?,?,?,?)",
                cardId, userId, attachment.sourceRef(), attachment.title(), attachment.url(),
                attachment.publisher(), attachment.snippet());
    }

    /** 计算卡片已有附件数量，用于决定下一个 sourceRef 编号。 */
    public int attachmentCount(long userId, long cardId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM research_attachment "
                + "WHERE card_id=? AND user_id=?", Integer.class, cardId, userId);
        return count == null ? 0 : count;
    }

    /** 查询用户的所有调研卡片，按更新时间倒序。 */
    public List<CardRow> listCards(long userId) {
        return jdbc.query("SELECT * FROM research_card WHERE user_id=? ORDER BY updated_at DESC,id DESC",
                (rs, n) -> card(rs), userId);
    }

    /** 查询单个卡片，校验用户归属。 */
    public Optional<CardRow> findCard(long userId, long cardId) {
        return jdbc.query("SELECT * FROM research_card WHERE id=? AND user_id=?",
                (rs, n) -> card(rs), cardId, userId).stream().findFirst();
    }

    /** 更新卡片标题和简述。 */
    public void updateCard(long userId, long cardId, String title, String brief) {
        jdbc.update("UPDATE research_card SET title=?,brief=? WHERE id=? AND user_id=?",
                title, brief, cardId, userId);
    }

    /** 删除卡片及其关联的附件、来源、消息、论坛和运行记录。 */
    @Transactional
    public void deleteCard(long userId, long cardId) {
        jdbc.update("DELETE FROM research_attachment WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM research_source WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM research_message WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM research_forum WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM research_run WHERE card_id=? AND user_id=?", cardId, userId);
        jdbc.update("DELETE FROM research_card WHERE id=? AND user_id=?", cardId, userId);
    }

    /** 添加对话消息。 */
    public long addMessage(long userId, long cardId, String role, String agent, String content) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO research_message(card_id,user_id,role,agent,content) VALUES(?,?,?,?,?)",
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

    /** 锁定卡片归属并原子保存 user/assistant，失败时整轮回滚。 */
    @Transactional
    public MessageIds addExchange(long userId, long cardId, String question, String answer) {
        List<Long> owned = jdbc.query("SELECT id FROM research_card WHERE id=? AND user_id=? FOR UPDATE",
                (rs, n) -> rs.getLong("id"), cardId, userId);
        if (owned.isEmpty()) throw new BizException(404, "调研卡片不存在");
        long userMessageId = addMessage(userId, cardId, "user", null, question);
        long assistantMessageId = addMessage(userId, cardId, "assistant", "planner", answer);
        if (userMessageId <= 0 || assistantMessageId <= 0) {
            throw new IllegalStateException("未生成完整对话消息 ID");
        }
        return new MessageIds(userMessageId, assistantMessageId);
    }

    /** 查询卡片的所有对话消息，按 ID 顺序。 */
    public List<MessageRow> messages(long userId, long cardId) {
        return jdbc.query("SELECT id,card_id,role,agent,content,created_at FROM research_message "
                        + "WHERE card_id=? AND user_id=? ORDER BY id",
                (rs, n) -> new MessageRow(rs.getLong("id"), rs.getLong("card_id"),
                        rs.getString("role"), rs.getString("agent"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), cardId, userId);
    }

    /** 创建调研运行任务：加锁检查防止重复启动，初始阶段 planning。 */
    @Transactional
    public long createRun(long userId, long cardId) {
        jdbc.queryForObject("SELECT id FROM research_card WHERE id=? AND user_id=? FOR UPDATE",
                Long.class, cardId, userId);
        Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM research_run "
                + "WHERE card_id=? AND user_id=? AND status='RUNNING'", Integer.class, cardId, userId);
        if (running != null && running > 0) throw new BizException(409, "已有调研任务正在运行");
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO research_run(card_id,user_id,status,current_stage,progress) "
                            + "VALUES(?,?,'RUNNING','planning',5)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, cardId);
            statement.setLong(2, userId);
            return statement;
        }, key);
        jdbc.update("UPDATE research_card SET status='RESEARCHING',current_stage='planning',"
                + "progress=5,last_error=NULL WHERE id=? AND user_id=?", cardId, userId);
        if (key.getKey() == null) throw new IllegalStateException("未生成调研任务 ID");
        return key.getKey().longValue();
    }

    /** 查询单个运行任务。 */
    public Optional<RunRow> findRun(long userId, long cardId, long runId) {
        return jdbc.query("SELECT * FROM research_run WHERE id=? AND card_id=? AND user_id=?",
                (rs, n) -> run(rs), runId, cardId, userId).stream().findFirst();
    }

    /** 查询卡片当前运行中的任务。 */
    public Optional<RunRow> activeRun(long userId, long cardId) {
        return jdbc.query("SELECT * FROM research_run WHERE card_id=? AND user_id=? "
                        + "AND status='RUNNING' ORDER BY id DESC LIMIT 1",
                (rs, n) -> run(rs), cardId, userId).stream().findFirst();
    }

    /** 查询卡片最近一次运行(任意状态)的 ID，用于工作台初次加载还原论坛流。无则返回 null。 */
    public Long lastRunId(long userId, long cardId) {
        return jdbc.query("SELECT id FROM research_run WHERE card_id=? AND user_id=? "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getLong("id"), cardId, userId).stream().findFirst().orElse(null);
    }

    /** 读取运行检查点；空值表示尚未完成任何可复用阶段。 */
    public String runCheckpoint(long userId, long cardId, long runId) {
        List<String> checkpoints = jdbc.query(
                "SELECT checkpoint_json FROM research_run WHERE id=? AND card_id=? AND user_id=?",
                (rs, n) -> rs.getString("checkpoint_json"), runId, cardId, userId);
        return checkpoints.isEmpty() ? null : checkpoints.get(0);
    }

    /** 原子保存最近完成阶段的检查点。 */
    public void saveCheckpoint(long userId, long cardId, long runId, String checkpointJson) {
        jdbc.update("UPDATE research_run SET checkpoint_json=? WHERE id=? AND card_id=? AND user_id=? "
                        + "AND status='RUNNING'",
                checkpointJson, runId, cardId, userId);
    }

    /** 恢复最近一次失败任务，复用原 runId、进度和检查点。 */
    @Transactional
    public RunRow resumeLastFailed(long userId, long cardId) {
        jdbc.queryForObject("SELECT id FROM research_card WHERE id=? AND user_id=? FOR UPDATE",
                Long.class, cardId, userId);
        Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM research_run "
                + "WHERE card_id=? AND user_id=? AND status='RUNNING'", Integer.class, cardId, userId);
        if (running != null && running > 0) throw new BizException(409, "已有调研任务正在运行");
        RunRow failed = jdbc.query("SELECT * FROM research_run WHERE card_id=? AND user_id=? "
                        + "AND status='FAILED' ORDER BY id DESC LIMIT 1",
                (rs, n) -> run(rs), cardId, userId).stream().findFirst()
                .orElseThrow(() -> new BizException(409, "没有可继续的中断任务"));
        jdbc.update("UPDATE research_run SET status='RUNNING',error_message=NULL,finished_at=NULL "
                        + "WHERE id=? AND card_id=? AND user_id=? AND status='FAILED'",
                failed.id(), cardId, userId);
        jdbc.update("UPDATE research_card SET status='RESEARCHING',current_stage=?,progress=?,last_error=NULL "
                        + "WHERE id=? AND user_id=?",
                failed.currentStage(), failed.progress(), cardId, userId);
        return new RunRow(failed.id(), failed.cardId(), "RUNNING", failed.currentStage(), failed.progress(),
                null, failed.startedAt(), null);
    }

    /** 更新任务进度和阶段，同步到卡片。 */
    public void updateProgress(long userId, long cardId, long runId, String stage, int progress) {
        jdbc.update("UPDATE research_run SET current_stage=?,progress=? WHERE id=? AND user_id=?",
                stage, progress, runId, userId);
        jdbc.update("UPDATE research_card SET current_stage=?,progress=? WHERE id=? AND user_id=?",
                stage, progress, cardId, userId);
    }

    /** 判断任务是否仍在运行中。 */
    public boolean isRunRunning(long userId, long runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM research_run "
                + "WHERE id=? AND user_id=? AND status='RUNNING'", Integer.class, runId, userId);
        return count != null && count > 0;
    }

    /** 取消运行中的任务，恢复卡片状态。 */
    public void cancelRun(long userId, long cardId, long runId) {
        jdbc.update("UPDATE research_run SET status='CANCELLED',current_stage='cancelled',"
                        + "finished_at=NOW() WHERE id=? AND card_id=? AND user_id=? AND status='RUNNING'",
                runId, cardId, userId);
        jdbc.update("UPDATE research_card SET status=IF(graph_json IS NULL,'DRAFT','COMPLETED'),"
                        + "current_stage=IF(graph_json IS NULL,'cancelled','completed'),"
                        + "progress=IF(graph_json IS NULL,0,100) WHERE id=? AND user_id=?",
                cardId, userId);
    }

    /** 完成调研任务：保存来源、更新运行记录和卡片状态(含报告 IR)。 */
    @Transactional
    public void complete(long userId, long cardId, long runId, String graphJson, String reportIr,
                         String reportMd, int nodeCount, int edgeCount, List<SourceInput> sources) {
        jdbc.update("DELETE FROM research_source WHERE card_id=? AND user_id=?", cardId, userId);
        for (SourceInput source : sources) {
            jdbc.update("INSERT INTO research_source(card_id,user_id,source_ref,title,url,publisher,snippet) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    cardId, userId, source.sourceRef(), source.title(), source.url(),
                    source.publisher(), source.snippet());
        }
        jdbc.update("UPDATE research_run SET status='COMPLETED',current_stage='completed',progress=100,"
                        + "finished_at=NOW(),error_message=NULL WHERE id=? AND user_id=?",
                runId, userId);
        jdbc.update("UPDATE research_card SET status='COMPLETED',current_stage='completed',progress=100,"
                        + "graph_json=?,report_ir=?,report_md=?,node_count=?,edge_count=?,last_error=NULL "
                        + "WHERE id=? AND user_id=?",
                graphJson, reportIr, reportMd, nodeCount, edgeCount, cardId, userId);
    }

    /** 标记任务失败，记录错误信息。 */
    public void fail(long userId, long cardId, long runId, String error) {
        String safe = error == null ? "调研失败" : error.substring(0, Math.min(error.length(), 1000));
        int updated = jdbc.update("UPDATE research_run SET status='FAILED',error_message=?,finished_at=NOW() "
                + "WHERE id=? AND user_id=? AND status='RUNNING'", safe, runId, userId);
        if (updated > 0) {
            jdbc.update("UPDATE research_card SET status='FAILED',last_error=? WHERE id=? AND user_id=?",
                    safe, cardId, userId);
        }
    }

    /**
     * 服务启动时收口上一个进程遗留的运行中任务。检查点和当前进度保持不变，
     * 用户可通过断点续跑继续；不能把已丢失执行线程的任务继续伪装成运行中。
     */
    @Transactional
    public int failInterruptedRuns(String error) {
        String safe = error == null ? "服务重启导致调研中断" : error.substring(0, Math.min(error.length(), 1000));
        jdbc.update("UPDATE research_card c SET status='FAILED',last_error=? "
                        + "WHERE c.status='RESEARCHING' AND EXISTS (SELECT 1 FROM research_run r "
                        + "WHERE r.card_id=c.id AND r.user_id=c.user_id AND r.status='RUNNING')",
                safe);
        return jdbc.update("UPDATE research_run SET status='FAILED',error_message=?,finished_at=NOW() "
                        + "WHERE status='RUNNING'",
                safe);
    }

    /** 查询卡片的调研来源列表。 */
    public List<SourceRow> sources(long userId, long cardId) {
        return jdbc.query("SELECT id,card_id,source_ref,title,url,publisher,snippet FROM research_source "
                        + "WHERE card_id=? AND user_id=? ORDER BY id",
                (rs, n) -> new SourceRow(rs.getLong("id"), rs.getLong("card_id"),
                        rs.getString("source_ref"), rs.getString("title"), rs.getString("url"),
                        rs.getString("publisher"), rs.getString("snippet")), cardId, userId);
    }

    /** 查询卡片的用户附件列表。 */
    public List<AttachmentRow> attachments(long userId, long cardId) {
        return jdbc.query("SELECT id,card_id,source_ref,title,url,publisher,snippet FROM research_attachment "
                        + "WHERE card_id=? AND user_id=? ORDER BY id",
                (rs, n) -> new AttachmentRow(rs.getLong("id"), rs.getLong("card_id"),
                        rs.getString("source_ref"), rs.getString("title"), rs.getString("url"),
                        rs.getString("publisher"), rs.getString("snippet")), cardId, userId);
    }

    /** 写入一条论坛事件。 */
    public void addForumEvent(long userId, long cardId, long runId, String source, String content) {
        jdbc.update("INSERT INTO research_forum(card_id,user_id,run_id,source,content,created_at) "
                        + "VALUES(?,?,?,?,?,?)",
                cardId, userId, runId, source, content, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** 查询某次运行的论坛事件(按时间顺序)，用于工作台初次加载还原协作流。 */
    public List<ForumEventRow> forumEvents(long cardId, long runId) {
        return jdbc.query("SELECT id,card_id,run_id,user_id,source,content,created_at FROM research_forum "
                        + "WHERE card_id=? AND run_id=? ORDER BY id",
                (rs, n) -> new ForumEventRow(rs.getLong("id"), rs.getLong("card_id"), rs.getLong("run_id"),
                        rs.getLong("user_id"), rs.getString("source"), rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()), cardId, runId);
    }

    private CardRow card(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CardRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                rs.getString("brief"), rs.getString("status"), rs.getString("current_stage"),
                rs.getInt("progress"), rs.getInt("node_count"), rs.getInt("edge_count"),
                rs.getString("graph_json"), rs.getString("report_ir"), rs.getString("report_md"),
                rs.getString("last_error"),
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

