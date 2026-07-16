package com.sparrow.ai.infrastructure.persistence;

import com.sparrow.common.ai.AbstractAgentConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiAgentConfigRepository extends AbstractAgentConfigRepository {

    public AiAgentConfigRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override protected String tableName()        { return "ai_agent_config"; }
    @Override protected String auditTableName()   { return "ai_agent_config_audit"; }
    @Override protected String auditIndexName()   { return "idx_ai_agent_audit_key"; }
    @Override protected String serviceName()      { return "sparrow-ai"; }
}
