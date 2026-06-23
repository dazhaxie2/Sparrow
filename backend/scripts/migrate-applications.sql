-- 「应用与产业链」AI 判定缓存表(对已初始化的库执行)。
-- 幂等:CREATE TABLE IF NOT EXISTS 重复执行不报错。
-- 全新库无需此文件 —— 01-schema.sql 已包含此表。
SET NAMES utf8mb4;
USE sparrow_graph;

-- node_application:材料/化合物节点的下游应用邻居(LLM 判定后回写)。
-- 复合主键保证幂等,saveAll 时先 delete by node_id 再批量插入以支持重算。
CREATE TABLE IF NOT EXISTS node_application (
    node_id     BIGINT NOT NULL COMMENT '材料/化合物节点(被应用的主体)',
    app_node_id BIGINT NOT NULL COMMENT '被判定为下游应用的邻居节点',
    PRIMARY KEY (node_id, app_node_id),
    KEY idx_node (node_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '节点应用/产业链判定缓存';
