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
CREATE DATABASE IF NOT EXISTS sparrow_industry_chain
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON sparrow.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_user.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_trade.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_graph.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_ai.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_industry_chain.* TO 'sparrow'@'%';
FLUSH PRIVILEGES;

USE sparrow_user;

CREATE TABLE IF NOT EXISTS t_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    username         VARCHAR(64)  NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    email            VARCHAR(128) NULL,
    role             VARCHAR(16)  NOT NULL DEFAULT 'user',
    member_expire_at DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email)
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
    -- 百万级改造:详情为空时跳转的外链(爬虫轻量抓取/合成节点用);现有节点保持 NULL
    source_url VARCHAR(512) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_era (era_rank),
    KEY idx_category (category),
    KEY idx_importance (importance),
    -- 百万级瓦片采样排序:消除 subgraph 候选查询 filesort(Phase3 文档已标 TODO)
    KEY idx_importance_id (importance DESC, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS tech_edge (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    from_id BIGINT NOT NULL,
    to_id   BIGINT NOT NULL,
    -- 关系类型:0=依赖/前置(默认,现有边全部如此),1=结构/分类归属,预留 2=衍生/应用…
    -- 爬虫"结构边"与人工"依赖边"混表后,靠此列区分布局聚类口径与前端筛选。
    relation TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_edge (from_id, to_id),
    KEY idx_to (to_id),
    -- 百万级:正向遍历(from_id 为起点)显式索引
    KEY idx_from (from_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 百万级 LOD 布局坐标:SFDP 离线预计算后按层级持久化。
-- level 0 = 顶层簇代表点(远观), level 3 = 叶节点精确坐标(最深处)。
CREATE TABLE IF NOT EXISTS node_layout (
    node_id    BIGINT  NOT NULL,
    cluster_id BIGINT  NOT NULL COMMENT '所属聚类簇(Louvain 离线算出)',
    level      TINYINT NOT NULL COMMENT 'LOD 层级 0=顶层簇,1,2,3=叶节点',
    x          DOUBLE  NOT NULL,
    y          DOUBLE  NOT NULL,
    PRIMARY KEY (node_id, level),
    KEY idx_cluster_level (cluster_id, level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 「应用与产业链」AI 判定结果缓存(按需懒计算):材料/化合物节点的下游应用邻居。
-- 由 sparrow-ai 的 LLM 判定后回写,首次请求触发计算,之后命中缓存秒出。
-- 复合主键保证幂等,saveAll 时先 delete by node_id 再批量插入以支持重算。
CREATE TABLE IF NOT EXISTS node_application (
    node_id     BIGINT NOT NULL COMMENT '材料/化合物节点(被应用的主体)',
    app_node_id BIGINT NOT NULL COMMENT '被判定为下游应用的邻居节点',
    PRIMARY KEY (node_id, app_node_id),
    KEY idx_node (node_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '节点应用/产业链判定缓存';

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

-- AI 聊天历史:会话(chat_session)与消息(chat_message)。
-- 首问截断作 title;消息只存 user 问题 + assistant 回答,不存 thinking(中间态,体积大)。
CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(120) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_session_user (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    mode       VARCHAR(16) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_message_session (session_id, id),
    KEY idx_chat_message_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

USE sparrow_industry_chain;

CREATE TABLE IF NOT EXISTS research_card (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    title         VARCHAR(120) NOT NULL,
    brief         TEXT         NULL,
    status        VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    current_stage VARCHAR(32)  NULL,
    progress      INT          NOT NULL DEFAULT 0,
    node_count    INT          NOT NULL DEFAULT 0,
    edge_count    INT          NOT NULL DEFAULT 0,
    graph_json    LONGTEXT     NULL,
    report_ir     LONGTEXT     NULL,
    report_md     LONGTEXT     NULL,
    last_error    VARCHAR(1000) NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_research_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS research_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    card_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL,
    agent      VARCHAR(32) NULL,
    content    TEXT        NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_research_message_card (card_id, id),
    KEY idx_research_message_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS research_run (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    card_id       BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    status        VARCHAR(24) NOT NULL,
    current_stage VARCHAR(32) NULL,
    progress      INT         NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    checkpoint_json LONGTEXT  NULL,
    started_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at   DATETIME    NULL,
    PRIMARY KEY (id),
    KEY idx_research_run_card (card_id, id),
    KEY idx_research_run_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS research_source (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    card_id     BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    source_ref  VARCHAR(16)   NOT NULL,
    title       VARCHAR(300)  NOT NULL,
    url         VARCHAR(1200) NOT NULL,
    publisher   VARCHAR(160)  NULL,
    snippet     VARCHAR(1200) NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_research_source_ref (card_id, source_ref),
    KEY idx_research_source_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS research_attachment (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    card_id     BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    source_ref  VARCHAR(16)   NOT NULL,
    title       VARCHAR(300)  NOT NULL,
    url         VARCHAR(1200) NOT NULL,
    publisher   VARCHAR(160)  NULL,
    snippet     VARCHAR(3000) NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_research_attachment_ref (card_id, source_ref),
    KEY idx_research_attachment_card (card_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS research_forum (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    card_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    run_id     BIGINT      NOT NULL,
    source     VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_research_forum_run (card_id, run_id, id),
    KEY idx_research_forum_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Service-owned Agent prompt/runtime configuration. Defaults are seeded by each service.
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
