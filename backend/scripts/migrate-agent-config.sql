-- Additive Agent prompt/runtime configuration tables for existing environments.
CREATE DATABASE IF NOT EXISTS sparrow_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sparrow_industry_chain DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE sparrow_ai;
CREATE TABLE IF NOT EXISTS ai_agent_config (
    agent_key VARCHAR(64) NOT NULL, display_name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL, system_prompt MEDIUMTEXT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1, max_context_messages INT NOT NULL,
    max_context_chars INT NOT NULL, max_output_chars INT NOT NULL, max_steps INT NOT NULL,
    updated_by BIGINT NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_agent_config_audit (
    id BIGINT NOT NULL AUTO_INCREMENT, agent_key VARCHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL, action VARCHAR(24) NOT NULL, summary VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(id),
    KEY idx_ai_agent_audit_key(agent_key,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE sparrow_industry_chain;
CREATE TABLE IF NOT EXISTS agent_config (
    agent_key VARCHAR(64) NOT NULL, display_name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL, system_prompt MEDIUMTEXT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1, max_context_messages INT NOT NULL,
    max_context_chars INT NOT NULL, max_output_chars INT NOT NULL, max_steps INT NOT NULL,
    updated_by BIGINT NULL, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_config_audit (
    id BIGINT NOT NULL AUTO_INCREMENT, agent_key VARCHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL, action VARCHAR(24) NOT NULL, summary VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(id),
    KEY idx_agent_config_audit_key(agent_key,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
