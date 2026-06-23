-- 百万级图谱改造增量 schema(对已初始化的库执行)。
-- 幂等:重复执行不报错(source_url 列已存在 / 索引已存在时静默跳过)。
-- 全新库无需此文件 —— 01-schema.sql 已包含这些列/索引/表。
SET NAMES utf8mb4;
USE sparrow_graph;

-- 1. tech_node 加 source_url 列(详情为空时跳转外链)
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'sparrow_graph' AND TABLE_NAME = 'tech_node' AND COLUMN_NAME = 'source_url'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tech_node ADD COLUMN source_url VARCHAR(512) NULL AFTER importance',
    'SELECT "source_url 已存在,跳过" AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. tech_node 加 (importance DESC, id) 复合索引(消除 subgraph filesort)
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'sparrow_graph' AND TABLE_NAME = 'tech_node' AND INDEX_NAME = 'idx_importance_id'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tech_node ADD KEY idx_importance_id (importance DESC, id)',
    'SELECT "idx_importance_id 已存在,跳过" AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. tech_edge 加 from_id 索引(正向遍历)
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'sparrow_graph' AND TABLE_NAME = 'tech_edge' AND INDEX_NAME = 'idx_from'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tech_edge ADD KEY idx_from (from_id)',
    'SELECT "idx_from 已存在,跳过" AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. node_layout 坐标表(SFDP 预计算结果)
CREATE TABLE IF NOT EXISTS node_layout (
    node_id    BIGINT  NOT NULL,
    cluster_id BIGINT  NOT NULL COMMENT '所属聚类簇(Louvain 离线算出)',
    level      TINYINT NOT NULL COMMENT 'LOD 层级 0=顶层簇,1,2,3=叶节点',
    x          DOUBLE  NOT NULL,
    y          DOUBLE  NOT NULL,
    PRIMARY KEY (node_id, level),
    KEY idx_cluster_level (cluster_id, level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 5. tech_edge 加 relation 列(关系类型:0=依赖/前置,1=结构/分类归属)
-- 存量边一律回填 0,语义不变;爬虫后续按类型写入结构边。
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'sparrow_graph' AND TABLE_NAME = 'tech_edge' AND COLUMN_NAME = 'relation'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tech_edge ADD COLUMN relation TINYINT NOT NULL DEFAULT 0 AFTER to_id',
    'SELECT "relation 已存在,跳过" AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
