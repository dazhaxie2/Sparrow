-- 用户收藏夹与节点收藏表
-- 依赖: sparrow_graph.user_favorite_folder / user_favorite_item
-- 说明: 将原前端 localStorage 学习状态(想学/已读/已掌握)替换为登录用户后端持久化的自定义收藏夹。
--       一个节点在同一用户下只能属于一个收藏夹,支持移动到其它收藏夹。

USE sparrow_graph;

CREATE TABLE IF NOT EXISTS user_favorite_folder (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL COMMENT '用户 id',
    name        VARCHAR(64)  NOT NULL COMMENT '收藏夹名称',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序权重,越小越靠前',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_folder_name (user_id, name),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户收藏夹';

CREATE TABLE IF NOT EXISTS user_favorite_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL COMMENT '用户 id',
    folder_id   BIGINT       NOT NULL COMMENT '所属收藏夹 id',
    node_id     BIGINT       NOT NULL COMMENT '科技节点 id',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_node (user_id, node_id),
    KEY idx_user_folder (user_id, folder_id),
    KEY idx_node_id (node_id),
    CONSTRAINT fk_item_folder FOREIGN KEY (folder_id) REFERENCES user_favorite_folder(id) ON DELETE CASCADE,
    CONSTRAINT fk_item_node FOREIGN KEY (node_id) REFERENCES tech_node(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户收藏的节点';

-- 幂empotent: 已存在同名系统默认收藏夹时不重复创建(由应用层在首次使用时创建,避免脚本依赖固定 id)。
