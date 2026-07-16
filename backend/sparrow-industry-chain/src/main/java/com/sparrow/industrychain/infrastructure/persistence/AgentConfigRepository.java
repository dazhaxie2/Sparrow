package com.sparrow.industrychain.infrastructure.persistence;

import com.sparrow.common.ai.AbstractAgentConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentConfigRepository extends AbstractAgentConfigRepository {

    public AgentConfigRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override protected String tableName()        { return "agent_config"; }
    @Override protected String auditTableName()   { return "agent_config_audit"; }
    @Override protected String auditIndexName()   { return "idx_agent_config_audit_key"; }
    @Override protected String serviceName()      { return "sparrow-industry-chain"; }
}
