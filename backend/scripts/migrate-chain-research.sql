-- 用户私有产业链深度调研：卡片、对话、异步任务与联网来源。
SET NAMES utf8mb4;
USE sparrow_ai;

CREATE TABLE IF NOT EXISTS chain_research_card (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL, brief TEXT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT', current_stage VARCHAR(32) NULL,
    progress INT NOT NULL DEFAULT 0, node_count INT NOT NULL DEFAULT 0,
    edge_count INT NOT NULL DEFAULT 0, graph_json LONGTEXT NULL,
    report_md LONGTEXT NULL, last_error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id), KEY idx_chain_research_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chain_research_message (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL, agent VARCHAR(32) NULL, content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), KEY idx_chain_research_message_card (card_id, id),
    KEY idx_chain_research_message_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chain_research_run (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL, current_stage VARCHAR(32) NULL, progress INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL, started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL, PRIMARY KEY (id),
    KEY idx_chain_research_run_card (card_id, id), KEY idx_chain_research_run_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chain_research_source (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    source_ref VARCHAR(16) NOT NULL, title VARCHAR(300) NOT NULL,
    url VARCHAR(1200) NOT NULL, publisher VARCHAR(160) NULL, snippet VARCHAR(1200) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), UNIQUE KEY uk_chain_research_source_ref (card_id, source_ref),
    KEY idx_chain_research_source_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
