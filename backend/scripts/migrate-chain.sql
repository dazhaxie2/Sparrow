-- 产业链专题库(对已初始化的库执行)。
-- 幂等:CREATE DATABASE/TABLE IF NOT EXISTS 重复执行不报错。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS sparrow_chain
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON sparrow_chain.* TO 'sparrow'@'%';
FLUSH PRIVILEGES;

USE sparrow_chain;

-- 产业链主表。
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

-- 供应链边:from 供货给 to(有向)。
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

-- 先写入四条专题元数据；实际节点和边由 chain_sync.py 幂等回填。
INSERT INTO chain (slug, name, description, cover_color) VALUES
('nvidia-ai', '英伟达 / AI 芯片链', '以英伟达 GPU 为核心的 AI 算力供应链：代工、HBM 内存、光刻、设计授权等。', '#76b900'),
('apple-consumer', '苹果消费电子链', '以 iPhone/Mac 为核心的消费电子供应链：代工组装、显示、声学、玻璃、芯片。', '#555555'),
('tesla-ev', '特斯拉电动车链', '以特斯拉为核心的电动车供应链：动力电池、电机、自动驾驶芯片、热管理等。', '#cc0000'),
('spacex-aerospace', 'SpaceX 航天链', '以 SpaceX 为核心的航天供应链：火箭发动机、卫星、发射服务、结构件等。', '#0066cc')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    cover_color = VALUES(cover_color);
