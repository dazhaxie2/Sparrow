-- 产业链调研独立服务迁移：sparrow_ai.chain_research_* -> sparrow_industry_chain.research_*。
-- 幂等：可重复执行，保留原 ID，目标行存在时更新为旧表当前值。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS sparrow_industry_chain
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON sparrow_industry_chain.* TO 'sparrow'@'%';
FLUSH PRIVILEGES;

CREATE DATABASE IF NOT EXISTS sparrow_ai
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 旧表不存在时建空表，允许全新环境执行迁移脚本不报错。
CREATE TABLE IF NOT EXISTS sparrow_ai.chain_research_card (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL, brief TEXT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT', current_stage VARCHAR(32) NULL,
    progress INT NOT NULL DEFAULT 0, node_count INT NOT NULL DEFAULT 0,
    edge_count INT NOT NULL DEFAULT 0, graph_json LONGTEXT NULL,
    report_md LONGTEXT NULL, last_error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @has_report_ir := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'sparrow_ai' AND TABLE_NAME = 'chain_research_card' AND COLUMN_NAME = 'report_ir'
);
SET @ddl := IF(@has_report_ir = 0,
    'ALTER TABLE sparrow_ai.chain_research_card ADD COLUMN report_ir LONGTEXT NULL AFTER report_md',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS sparrow_ai.chain_research_message (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL, agent VARCHAR(32) NULL, content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sparrow_ai.chain_research_run (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL, current_stage VARCHAR(32) NULL, progress INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL, checkpoint_json LONGTEXT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @has_chain_checkpoint := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'sparrow_ai'
      AND TABLE_NAME = 'chain_research_run'
      AND COLUMN_NAME = 'checkpoint_json'
);
SET @ddl := IF(
    @has_chain_checkpoint = 0,
    'ALTER TABLE sparrow_ai.chain_research_run ADD COLUMN checkpoint_json LONGTEXT NULL AFTER error_message',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS sparrow_ai.chain_research_source (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    source_ref VARCHAR(16) NOT NULL, title VARCHAR(300) NOT NULL,
    url VARCHAR(1200) NOT NULL, publisher VARCHAR(160) NULL, snippet VARCHAR(1200) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sparrow_ai.chain_research_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    source_ref VARCHAR(16) NOT NULL, title VARCHAR(300) NOT NULL,
    url VARCHAR(1200) NOT NULL, publisher VARCHAR(160) NULL, snippet VARCHAR(3000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sparrow_ai.chain_research_forum (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL, source VARCHAR(16) NOT NULL, content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE sparrow_industry_chain;

CREATE TABLE IF NOT EXISTS research_card (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL, brief TEXT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT', current_stage VARCHAR(32) NULL,
    progress INT NOT NULL DEFAULT 0, node_count INT NOT NULL DEFAULT 0,
    edge_count INT NOT NULL DEFAULT 0, graph_json LONGTEXT NULL,
    report_ir LONGTEXT NULL, report_md LONGTEXT NULL, last_error VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id), KEY idx_research_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS research_message (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL, agent VARCHAR(32) NULL, content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), KEY idx_research_message_card (card_id, id),
    KEY idx_research_message_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS research_run (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL, current_stage VARCHAR(32) NULL, progress INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL, checkpoint_json LONGTEXT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL, PRIMARY KEY (id),
    KEY idx_research_run_card (card_id, id), KEY idx_research_run_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @has_checkpoint := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'sparrow_industry_chain'
      AND TABLE_NAME = 'research_run'
      AND COLUMN_NAME = 'checkpoint_json'
);
SET @ddl := IF(
    @has_checkpoint = 0,
    'ALTER TABLE sparrow_industry_chain.research_run ADD COLUMN checkpoint_json LONGTEXT NULL AFTER error_message',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS research_source (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    source_ref VARCHAR(16) NOT NULL, title VARCHAR(300) NOT NULL,
    url VARCHAR(1200) NOT NULL, publisher VARCHAR(160) NULL, snippet VARCHAR(1200) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), UNIQUE KEY uk_research_source_ref (card_id, source_ref),
    KEY idx_research_source_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS research_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    source_ref VARCHAR(16) NOT NULL, title VARCHAR(300) NOT NULL,
    url VARCHAR(1200) NOT NULL, publisher VARCHAR(160) NULL, snippet VARCHAR(3000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), UNIQUE KEY uk_research_attachment_ref (card_id, source_ref),
    KEY idx_research_attachment_card (card_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS research_forum (
    id BIGINT NOT NULL AUTO_INCREMENT, card_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL, source VARCHAR(16) NOT NULL, content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), KEY idx_research_forum_run (card_id, run_id, id),
    KEY idx_research_forum_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO research_card
    (id, user_id, title, brief, status, current_stage, progress, node_count, edge_count,
     graph_json, report_ir, report_md, last_error, created_at, updated_at)
SELECT id, user_id, title, brief, status, current_stage, progress, node_count, edge_count,
       graph_json, report_ir, report_md, last_error, created_at, updated_at
FROM sparrow_ai.chain_research_card
ON DUPLICATE KEY UPDATE
    title = VALUES(title), brief = VALUES(brief), status = VALUES(status),
    current_stage = VALUES(current_stage), progress = VALUES(progress),
    node_count = VALUES(node_count), edge_count = VALUES(edge_count),
    graph_json = VALUES(graph_json), report_ir = VALUES(report_ir),
    report_md = VALUES(report_md), last_error = VALUES(last_error),
    updated_at = VALUES(updated_at);

INSERT INTO research_message (id, card_id, user_id, role, agent, content, created_at)
SELECT id, card_id, user_id, role, agent, content, created_at
FROM sparrow_ai.chain_research_message
ON DUPLICATE KEY UPDATE
    role = VALUES(role), agent = VALUES(agent), content = VALUES(content);

INSERT INTO research_run
    (id, card_id, user_id, status, current_stage, progress, error_message, checkpoint_json, started_at, finished_at)
SELECT id, card_id, user_id, status, current_stage, progress, error_message, checkpoint_json, started_at, finished_at
FROM sparrow_ai.chain_research_run
ON DUPLICATE KEY UPDATE
    status = VALUES(status), current_stage = VALUES(current_stage), progress = VALUES(progress),
    error_message = VALUES(error_message), checkpoint_json = VALUES(checkpoint_json),
    finished_at = VALUES(finished_at);

INSERT INTO research_source (id, card_id, user_id, source_ref, title, url, publisher, snippet, created_at)
SELECT id, card_id, user_id, source_ref, title, url, publisher, snippet, created_at
FROM sparrow_ai.chain_research_source
ON DUPLICATE KEY UPDATE
    title = VALUES(title), url = VALUES(url), publisher = VALUES(publisher), snippet = VALUES(snippet);

INSERT INTO research_attachment (id, card_id, user_id, source_ref, title, url, publisher, snippet, created_at)
SELECT id, card_id, user_id, source_ref, title, url, publisher, snippet, created_at
FROM sparrow_ai.chain_research_attachment
ON DUPLICATE KEY UPDATE
    title = VALUES(title), url = VALUES(url), publisher = VALUES(publisher), snippet = VALUES(snippet);

INSERT INTO research_forum (id, card_id, user_id, run_id, source, content, created_at)
SELECT id, card_id, user_id, run_id, source, content, created_at
FROM sparrow_ai.chain_research_forum
ON DUPLICATE KEY UPDATE
    source = VALUES(source), content = VALUES(content);
