-- Sparrow Phase 2 M2 schema: split business schemas + Seata AT undo_log.
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS sparrow
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sparrow_user
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sparrow_trade
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sparrow_graph
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS sparrow_ai
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON sparrow.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_user.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_trade.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_graph.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_ai.* TO 'sparrow'@'%';
FLUSH PRIVILEGES;

USE sparrow_user;

CREATE TABLE IF NOT EXISTS t_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    username         VARCHAR(64)  NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    member_expire_at DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- M3 订单支付事件审计流水:消费 sparrow.trade.order-paid,order_no 唯一键天然幂等。
CREATE TABLE IF NOT EXISTS member_grant_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(32) NOT NULL,
    user_id      BIGINT      NOT NULL,
    product_code VARCHAR(32) NOT NULL,
    member_days  INT         NOT NULL,
    amount_cent  INT         NOT NULL,
    event_id     VARCHAR(64) NOT NULL,
    paid_at      DATETIME    NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
    xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context       VARCHAR(128) NOT NULL COMMENT 'undo_log context',
    rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
    log_status    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE sparrow_trade;

CREATE TABLE IF NOT EXISTS t_order (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(32) NOT NULL,
    user_id      BIGINT      NOT NULL,
    product_code VARCHAR(32) NOT NULL,
    product_name VARCHAR(64) NOT NULL,
    amount_cent  INT         NOT NULL,
    status       VARCHAR(16) NOT NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at      DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
    xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context       VARCHAR(128) NOT NULL COMMENT 'undo_log context',
    rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
    log_status    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE sparrow_graph;

CREATE TABLE IF NOT EXISTS tech_node (
    id         BIGINT       NOT NULL,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    era        VARCHAR(32)  NOT NULL,
    era_rank   INT          NOT NULL,
    year_label VARCHAR(32)  NULL,
    summary    VARCHAR(512) NOT NULL,
    detail     TEXT         NULL,
    premium    TINYINT      NOT NULL DEFAULT 0,
    -- 维基级扩容:领域轴(14 类之一)+ 重要度(LOD 取舍,既有 77 节点回填为 100)
    category   VARCHAR(32)  NULL,
    importance INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_era (era_rank),
    KEY idx_category (category),
    KEY idx_importance (importance)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS tech_edge (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    from_id BIGINT NOT NULL,
    to_id   BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_edge (from_id, to_id),
    KEY idx_to (to_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
    xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context       VARCHAR(128) NOT NULL COMMENT 'undo_log context',
    rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
    log_status    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

USE sparrow_ai;

CREATE TABLE IF NOT EXISTS rag_index_state (
    id          INT         NOT NULL PRIMARY KEY,
    fingerprint VARCHAR(80) NOT NULL,
    node_count  INT         NOT NULL,
    chunk_count INT         NOT NULL,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS kafka_consumed_event (
    consumer   VARCHAR(64)  NOT NULL,
    event_id   VARCHAR(128) NOT NULL,
    topic      VARCHAR(128) NOT NULL,
    consumed_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer, event_id),
    KEY idx_topic_consumed_at (topic, consumed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
