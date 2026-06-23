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
CREATE DATABASE IF NOT EXISTS sparrow_chain
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON sparrow.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_user.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_trade.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_graph.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_ai.* TO 'sparrow'@'%';
GRANT ALL PRIVILEGES ON sparrow_chain.* TO 'sparrow'@'%';
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

-- ── 产业链专题(sparrow_chain 库,与科技树图谱语义隔离) ──
USE sparrow_chain;

-- 产业链主表:每条对应一条独立供应链(英伟达链/苹果链/特斯拉链/SpaceX链)。
CREATE TABLE IF NOT EXISTS chain (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    slug        VARCHAR(64)  NOT NULL COMMENT 'URL 友好标识(nvidia-ai 等)',
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    cover_color VARCHAR(16)  NULL COMMENT '列表卡片主题色',
    PRIMARY KEY (id),
    UNIQUE KEY uk_slug (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 供应链节点:核心公司 / 供应商 / 代工厂 / 材料商。
CREATE TABLE IF NOT EXISTS chain_node (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    chain_id   BIGINT      NOT NULL,
    name       VARCHAR(160) NOT NULL,
    node_type  VARCHAR(32) NULL COMMENT '核心公司/供应商/代工厂/材料商',
    summary    TEXT,
    importance INT         NOT NULL DEFAULT 0 COMMENT '度中心度,决定节点大小',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chain_name (chain_id, name),
    KEY idx_chain (chain_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 供应链边:from 供货给 to(有向)。edge_type 区分供货/代工/材料供应/授权。
CREATE TABLE IF NOT EXISTS chain_edge (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    chain_id  BIGINT      NOT NULL,
    from_id   BIGINT      NOT NULL COMMENT '供应方节点 id',
    to_id     BIGINT      NOT NULL COMMENT '被供应方节点 id',
    edge_type VARCHAR(32) NULL COMMENT '供货/代工/材料供应/授权',
    product   VARCHAR(200) NULL COMMENT '具体供应的产品或环节',
    PRIMARY KEY (id),
    UNIQUE KEY uk_edge (from_id, to_id, edge_type),
    KEY idx_chain (chain_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
