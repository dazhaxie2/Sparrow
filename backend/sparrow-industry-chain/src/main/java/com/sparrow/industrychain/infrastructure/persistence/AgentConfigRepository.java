package com.sparrow.industrychain.infrastructure.persistence;

import com.sparrow.common.ai.AiAgentProfile;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentConfigRepository {

    private static final String SERVICE = "sparrow-industry-chain";
    private final JdbcTemplate jdbc;

    public AgentConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_config ("
                + "agent_key VARCHAR(64) NOT NULL,display_name VARCHAR(120) NOT NULL,"
                + "description VARCHAR(500) NOT NULL,system_prompt MEDIUMTEXT NOT NULL,"
                + "enabled TINYINT(1) NOT NULL DEFAULT 1,max_context_messages INT NOT NULL,"
                + "max_context_chars INT NOT NULL,max_output_chars INT NOT NULL,max_steps INT NOT NULL,"
                + "updated_by BIGINT NULL,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP "
                + "ON UPDATE CURRENT_TIMESTAMP,PRIMARY KEY(agent_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_config_audit ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,agent_key VARCHAR(64) NOT NULL,operator_id BIGINT NOT NULL,"
                + "action VARCHAR(24) NOT NULL,summary VARCHAR(500) NOT NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(id),"
                + "KEY idx_agent_config_audit_key(agent_key,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public void ensureDefault(AiAgentProfile profile) {
        jdbc.update("INSERT IGNORE INTO agent_config(agent_key,display_name,description,system_prompt,"
                        + "enabled,max_context_messages,max_context_chars,max_output_chars,max_steps) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)",
                profile.agentKey(), profile.displayName(), profile.description(), profile.systemPrompt(),
                profile.enabled(), profile.maxContextMessages(), profile.maxContextChars(),
                profile.maxOutputChars(), profile.maxSteps());
    }

    public List<AiAgentProfile> list() {
        return jdbc.query(selectSql() + " ORDER BY agent_key", (rs, row) -> map(rs));
    }

    public Optional<AiAgentProfile> find(String agentKey) {
        List<AiAgentProfile> rows = jdbc.query(selectSql() + " WHERE agent_key=?",
                (rs, row) -> map(rs), agentKey);
        return rows.stream().findFirst();
    }

    public int update(String agentKey, String systemPrompt, boolean enabled,
                      int maxContextMessages, int maxContextChars,
                      int maxOutputChars, int maxSteps, long operatorId) {
        return jdbc.update("UPDATE agent_config SET system_prompt=?,enabled=?,max_context_messages=?,"
                        + "max_context_chars=?,max_output_chars=?,max_steps=?,updated_by=?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE agent_key=?",
                systemPrompt, enabled, maxContextMessages, maxContextChars,
                maxOutputChars, maxSteps, operatorId, agentKey);
    }

    public void audit(String agentKey, long operatorId, String summary) {
        jdbc.update("INSERT INTO agent_config_audit(agent_key,operator_id,action,summary) VALUES(?,?,?,?)",
                agentKey, operatorId, "UPDATE", summary);
    }

    public List<AuditRow> audits(int limit) {
        return jdbc.query("SELECT id,agent_key,operator_id,action,summary,created_at "
                        + "FROM agent_config_audit ORDER BY id DESC LIMIT ?",
                (rs, row) -> new AuditRow(rs.getLong("id"), rs.getString("agent_key"),
                        rs.getLong("operator_id"), rs.getString("action"), rs.getString("summary"),
                        rs.getTimestamp("created_at").toInstant()), limit);
    }

    private String selectSql() {
        return "SELECT agent_key,display_name,description,system_prompt,enabled,max_context_messages,"
                + "max_context_chars,max_output_chars,max_steps,updated_by,updated_at FROM agent_config";
    }

    private AiAgentProfile map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long updatedBy = rs.getLong("updated_by");
        boolean updatedByWasNull = rs.wasNull();
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new AiAgentProfile(SERVICE, rs.getString("agent_key"), rs.getString("display_name"),
                rs.getString("description"), rs.getString("system_prompt"), rs.getBoolean("enabled"),
                rs.getInt("max_context_messages"), rs.getInt("max_context_chars"),
                rs.getInt("max_output_chars"), rs.getInt("max_steps"),
                updatedByWasNull ? null : updatedBy, updatedAt == null ? null : updatedAt.toInstant());
    }

    public record AuditRow(long id, String agentKey, long operatorId,
                           String action, String summary, Instant createdAt) {
    }
}
