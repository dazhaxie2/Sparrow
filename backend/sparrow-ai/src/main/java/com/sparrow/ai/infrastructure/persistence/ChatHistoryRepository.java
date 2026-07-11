package com.sparrow.ai.infrastructure.persistence;

import jakarta.annotation.PostConstruct;
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

/**
 * AI 聊天历史持久化:会话(chat_session)与消息(chat_message)。
 *
 * <p>用 JdbcTemplate,与 sparrow-ai 模块现有持久化风格一致(无 MyBatis-Plus)。
 * 范式参考 sparrow-industry-chain 的 IndustryChainRepository。</p>
 */
@Repository
public class ChatHistoryRepository {

    /** 会话行:用户的一次对话主题。 */
    public record ChatSessionRow(Long id, Long userId, String title,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 消息行:会话内的一条 user/assistant 问答。不存 thinking(中间态,体积大)。 */
    public record ChatMessageRow(Long id, Long sessionId, Long userId, String role,
                                 String content, String mode, LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;

    public ChatHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启动建表:IF NOT EXISTS,支持老库平滑升级(sparrow-ai 模块既有惯例)。 */
    @PostConstruct
    public void ensureTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_session ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,"
                + "title VARCHAR(120) NOT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "KEY idx_chat_session_user(user_id, updated_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_message ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "session_id BIGINT NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "role VARCHAR(16) NOT NULL,"
                + "content TEXT NOT NULL,"
                + "mode VARCHAR(16) NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "KEY idx_chat_message_session(session_id, id),"
                + "KEY idx_chat_message_user(user_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** 创建会话,返回自增主键。 */
    public long createSession(long userId, String title) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO chat_session(user_id, title) VALUES(?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, title);
            return ps;
        }, key);
        if (key.getKey() == null) {
            throw new IllegalStateException("未生成会话 ID");
        }
        return key.getKey().longValue();
    }

    /** 列出用户的所有会话,按更新时间倒序(最近活跃在前)。 */
    public List<ChatSessionRow> listSessions(long userId) {
        return jdbc.query(
                "SELECT id, user_id, title, created_at, updated_at FROM chat_session "
                        + "WHERE user_id = ? ORDER BY updated_at DESC",
                (rs, n) -> new ChatSessionRow(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                userId);
    }

    /** 查找指定会话(带归属校验,userId 不匹配返回空)。 */
    public Optional<ChatSessionRow> findSession(long userId, long sessionId) {
        return jdbc.query(
                "SELECT id, user_id, title, created_at, updated_at FROM chat_session "
                        + "WHERE id = ? AND user_id = ?",
                (rs, n) -> new ChatSessionRow(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                sessionId, userId)
                .stream().findFirst();
    }

    /** 删除会话(级联删除其下所有消息)。 */
    @Transactional
    public void deleteSession(long userId, long sessionId) {
        jdbc.update("DELETE FROM chat_message WHERE session_id = ? AND user_id = ?", sessionId, userId);
        jdbc.update("DELETE FROM chat_session WHERE id = ? AND user_id = ?", sessionId, userId);
    }

    /** 追加一条消息,返回消息 id。 */
    public long addMessage(long userId, long sessionId, String role, String content, String mode) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO chat_message(session_id, user_id, role, content, mode) VALUES(?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sessionId);
            ps.setLong(2, userId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, mode);
            return ps;
        }, key);
        if (key.getKey() == null) {
            throw new IllegalStateException("未生成消息 ID");
        }
        return key.getKey().longValue();
    }

    /**
     * 原子保存完整问答轮次。先锁定会话归属，再成对写入，避免 SSE/模型失败留下孤儿 user 消息。
     */
    @Transactional
    public void addExchange(long userId, long sessionId, String question, String answer, String mode) {
        List<Long> owned = jdbc.query("SELECT id FROM chat_session WHERE id = ? AND user_id = ? FOR UPDATE",
                (rs, n) -> rs.getLong("id"), sessionId, userId);
        if (owned.isEmpty()) {
            throw new IllegalStateException("会话不存在或归属不匹配");
        }
        addMessage(userId, sessionId, "user", question, null);
        addMessage(userId, sessionId, "assistant", answer, mode);
    }

    /** 取会话的全部消息(按时间正序,用于历史回放)。 */
    public List<ChatMessageRow> messages(long userId, long sessionId) {
        return jdbc.query(
                "SELECT id, session_id, user_id, role, content, mode, created_at FROM chat_message "
                        + "WHERE session_id = ? AND user_id = ? ORDER BY id ASC",
                (rs, n) -> new ChatMessageRow(
                        rs.getLong("id"), rs.getLong("session_id"), rs.getLong("user_id"),
                        rs.getString("role"), rs.getString("content"), rs.getString("mode"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                sessionId, userId);
    }

    /** 统计会话消息数(用于列表展示)。 */
    public int messageCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }
}
