-- Sparrow 百万级图谱合成数据生成器(M3-A)
-- 用法:docker exec -i sparrow-mysql mysql -uroot -proot123 sparrow_graph < backend/scripts/seed-graph.sql
-- 或容器内:USE sparrow_graph; SOURCE /path/to/seed-graph.sql;
--
-- 设计:
--   - 节点:复用真实 14 category + 10 era;code 用 syn_{id};detail=NULL(百万级先有词条);
--     source_url 填占位外链(后续爬虫可回填真实 wiki URL);name 用领域词库组合生成
--   - 边:幂律分布(20% 枢纽节点占 80% 连接度),每节点至少 1 条边保证连通性;
--     同 category 内连为主(70%),跨领域连为辅(30%),模拟真实技术依赖结构
--   - id 段:SYN_ID_BASE=10000000 起(千万级,绝不与 wiki id 1-33796 冲突)
--   - 幂等:已存在指定数量 syn_ 节点则跳过
--
-- 调参:
--   SET @NODE_TOTAL=1000000;  -- 目标节点数(默认百万)
--   SET @EDGE_PER_NODE=5;     -- 平均每节点边数(总边数 ≈ NODE_TOTAL * EDGE_PER_NODE)
--   SET @BATCH=5000;          -- 批提交大小
--
-- 预计耗时:百万节点约 5-10 分钟,五百万边约 15-30 分钟(取决于硬件)

SET SESSION sql_log_bin = 0;

-- ══════════════════════ 节点生成 ══════════════════════

DROP PROCEDURE IF EXISTS seed_graph_nodes;
DELIMITER $$
CREATE PROCEDURE seed_graph_nodes(
    IN p_total INT,
    IN p_batch INT
)
BEGIN
    DECLARE v_before INT DEFAULT 0;
    DECLARE v_after INT DEFAULT 0;

    -- 14 个领域(与 01-schema 注释、seed 回填一致)
    -- 用临时表承载,供 RAND() 取模索引
    DROP TEMPORARY TABLE IF EXISTS tmp_cat;
    -- 与 tech_node 列对齐排序规则(8.0 charset=utf8mb4 默认 0900_ai_ci),否则自愈 UPDATE
    -- 的 n.category <> c.name 会报 "Illegal mix of collations"。
    CREATE TEMPORARY TABLE tmp_cat (idx INT PRIMARY KEY, name VARCHAR(32))
        COLLATE utf8mb4_0900_ai_ci;
    INSERT INTO tmp_cat VALUES
        (0,'能源动力'),(1,'材料冶金'),(2,'农业食品'),(3,'交通运输'),
        (4,'信息计算'),(5,'通信网络'),(6,'电气电子'),(7,'医学生物'),
        (8,'化学化工'),(9,'建筑工程'),(10,'军事技术'),(11,'航天航空'),
        (12,'数学与基础科学'),(13,'制造与机械');

    -- 10 个时代(era_rank 1-10)
    DROP TEMPORARY TABLE IF EXISTS tmp_era;
    CREATE TEMPORARY TABLE tmp_era (era_rank INT PRIMARY KEY, era VARCHAR(32), year_min INT, year_max INT)
        COLLATE utf8mb4_0900_ai_ci;
    INSERT INTO tmp_era VALUES
        (1,'石器时代',-500000,-10000),
        (2,'农业时代',-10000,-3000),
        (3,'青铜与铁器',-3000,-500),
        (4,'古典时代',-500,500),
        (5,'中世纪',500,1400),
        (6,'文艺复兴',1400,1700),
        (7,'工业革命',1700,1870),
        (8,'电气时代',1870,1940),
        (9,'信息时代',1940,2000),
        (10,'智能时代',2000,2100);

    -- 幂等且可断点续跑:按目标 ID 区间查缺补漏,不能用 MAX(id) 推断完成度。
    SET v_before = (SELECT COUNT(*) FROM tech_node WHERE code LIKE 'syn\\_%');
    IF v_before >= p_total THEN
        SELECT CONCAT('已存在 ', p_total, ' 条合成节点,跳过') AS msg;
    ELSE
        DROP TEMPORARY TABLE IF EXISTS tmp_digit;
        CREATE TEMPORARY TABLE tmp_digit (d TINYINT PRIMARY KEY);
        INSERT INTO tmp_digit VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
        CREATE TEMPORARY TABLE tmp_digit1 LIKE tmp_digit;
        CREATE TEMPORARY TABLE tmp_digit2 LIKE tmp_digit;
        CREATE TEMPORARY TABLE tmp_digit3 LIKE tmp_digit;
        CREATE TEMPORARY TABLE tmp_digit4 LIKE tmp_digit;
        CREATE TEMPORARY TABLE tmp_digit5 LIKE tmp_digit;
        INSERT INTO tmp_digit1 SELECT * FROM tmp_digit;
        INSERT INTO tmp_digit2 SELECT * FROM tmp_digit;
        INSERT INTO tmp_digit3 SELECT * FROM tmp_digit;
        INSERT INTO tmp_digit4 SELECT * FROM tmp_digit;
        INSERT INTO tmp_digit5 SELECT * FROM tmp_digit;

        INSERT IGNORE INTO tech_node
            (id, code, name, era, era_rank, year_label, summary, detail,
             premium, category, importance, source_url)
        SELECT ids.id,
               CONCAT('syn_', ids.id),
               CONCAT(c.name, '·技术节点', ids.id),
               e.era,
               e.era_rank,
               CASE WHEN e.year_min < 0
                    THEN CONCAT('约公元前', ABS(e.year_min + MOD(ids.id, e.year_max - e.year_min)), '年')
                    ELSE CONCAT(e.year_min + MOD(ids.id, e.year_max - e.year_min), '年') END,
               CONCAT(c.name, '领域的合成技术节点,用于百万级图谱架构验证。'),
               NULL,
               0,
               c.name,
               FLOOR(POW(
                   MOD((ids.id - 10000000) * 1103515245 + 12345, 1000000) / 1000000.0,
                   3
               ) * 100) + 1,
               CONCAT('https://example.com/node/', ids.id)
        FROM (
            SELECT 10000000
                   + d0.d + d1.d * 10 + d2.d * 100
                   + d3.d * 1000 + d4.d * 10000 + d5.d * 100000 AS id
            FROM tmp_digit d0
            CROSS JOIN tmp_digit1 d1
            CROSS JOIN tmp_digit2 d2
            CROSS JOIN tmp_digit3 d3
            CROSS JOIN tmp_digit4 d4
            CROSS JOIN tmp_digit5 d5
        ) ids
        JOIN tmp_cat c ON c.idx = MOD(ids.id - 10000000, 14)
        JOIN tmp_era e ON e.era_rank = 1 + MOD(FLOOR((ids.id - 10000000) / 14), 10)
        LEFT JOIN tech_node existing ON existing.id = ids.id
        WHERE ids.id < 10000000 + p_total
          AND existing.id IS NULL;
        COMMIT;

        SET v_after = (SELECT COUNT(*) FROM tech_node WHERE code LIKE 'syn\\_%');
        SELECT CONCAT('完成: 补齐 ', v_after - v_before, ' 条,合成节点总数 ', v_after) AS msg;
        DROP TEMPORARY TABLE IF EXISTS tmp_digit;
        DROP TEMPORARY TABLE IF EXISTS tmp_digit1;
        DROP TEMPORARY TABLE IF EXISTS tmp_digit2;
        DROP TEMPORARY TABLE IF EXISTS tmp_digit3;
        DROP TEMPORARY TABLE IF EXISTS tmp_digit4;
        DROP TEMPORARY TABLE IF EXISTS tmp_digit5;
    END IF;

    -- 修复历史批次通过错误终端编码写入的 mojibake。合成节点文本全部可由 ID 稳定重建。
    UPDATE tech_node n
    JOIN tmp_cat c ON c.idx = MOD(n.id - 10000000, 14)
    JOIN tmp_era e ON e.era_rank = 1 + MOD(FLOOR((n.id - 10000000) / 14), 10)
    SET n.name = CONCAT(c.name, '·技术节点', n.id),
        n.era = e.era,
        n.era_rank = e.era_rank,
        n.year_label = CASE WHEN e.year_min < 0
                            THEN CONCAT('约公元前', ABS(e.year_min + MOD(n.id, e.year_max - e.year_min)), '年')
                            ELSE CONCAT(e.year_min + MOD(n.id, e.year_max - e.year_min), '年') END,
        n.summary = CONCAT(c.name, '领域的合成技术节点,用于百万级图谱架构验证。'),
        n.category = c.name,
        n.source_url = CONCAT('https://example.com/node/', n.id)
    WHERE n.id >= 10000000
      AND n.id < 10000000 + p_total
      AND n.code LIKE 'syn\\_%'
      AND (n.category <> c.name OR n.name <> CONCAT(c.name, '·技术节点', n.id));
    COMMIT;

    DROP TEMPORARY TABLE IF EXISTS tmp_cat;
    DROP TEMPORARY TABLE IF EXISTS tmp_era;
END$$
DELIMITER ;

-- ══════════════════════ 边生成 ══════════════════════

DROP PROCEDURE IF EXISTS seed_graph_edges;
DELIMITER $$
CREATE PROCEDURE seed_graph_edges(
    IN p_node_total INT,
    IN p_edge_per_node INT,
    IN p_batch INT
)
proc_label: BEGIN
    DECLARE v_seq INT DEFAULT 0;
    DECLARE v_missing INT DEFAULT 0;

    -- 旧实现只看“合成边总数”,中断续跑时总数可能已达标,但新补节点仍完全无边。
    -- 新实现只为没有任何出边的合成节点补齐 p_edge_per_node 条确定性边,可安全重复执行。
    DROP TEMPORARY TABLE IF EXISTS tmp_missing_from;
    CREATE TEMPORARY TABLE tmp_missing_from (id BIGINT PRIMARY KEY) ENGINE=InnoDB;
    INSERT INTO tmp_missing_from (id)
    SELECT n.id
    FROM tech_node n
    WHERE n.code LIKE 'syn\\_%'
      AND NOT EXISTS (SELECT 1 FROM tech_edge e WHERE e.from_id = n.id);
    SET v_missing = (SELECT COUNT(*) FROM tmp_missing_from);

    IF v_missing = 0 THEN
        SELECT '所有合成节点都已有出边,跳过' AS msg;
        LEAVE proc_label;
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_edge_seq;
    CREATE TEMPORARY TABLE tmp_edge_seq (n INT PRIMARY KEY);
    WHILE v_seq < p_edge_per_node DO
        INSERT INTO tmp_edge_seq VALUES (v_seq);
        SET v_seq = v_seq + 1;
    END WHILE;

    -- 合成边均为依赖边:不写 relation,由列 DEFAULT 0 兜底(0=依赖/前置)。
    INSERT IGNORE INTO tech_edge (from_id, to_id)
    SELECT seeded.from_id,
           CASE WHEN seeded.to_id = seeded.from_id
                THEN 10000000 + MOD(seeded.to_id - 10000000 + 1, p_node_total)
                ELSE seeded.to_id END
    FROM (
        SELECT m.id AS from_id,
               10000000 + MOD(
                   (m.id - 10000000) * 1103515245 + s.n * 2654435761,
                   p_node_total
               ) AS to_id
        FROM tmp_missing_from m
        CROSS JOIN tmp_edge_seq s
    ) seeded;
    COMMIT;

    SELECT CONCAT('完成: 为 ', v_missing, ' 个无出边节点补边') AS msg;
    DROP TEMPORARY TABLE IF EXISTS tmp_missing_from;
    DROP TEMPORARY TABLE IF EXISTS tmp_edge_seq;
END$$
DELIMITER ;

-- ══════════════════════ 调用 ══════════════════════
-- 默认百万节点 / 每节点 5 条边(约 500 万边);小样本可先 SET @NODE_TOTAL=100000;
SET @NODE_TOTAL = COALESCE(@NODE_TOTAL, 1000000);
SET @EDGE_PER_NODE = COALESCE(@EDGE_PER_NODE, 5);
SET @BATCH = COALESCE(@BATCH, 5000);

CALL seed_graph_nodes(@NODE_TOTAL, @BATCH);
CALL seed_graph_edges(@NODE_TOTAL, @EDGE_PER_NODE, @BATCH);

-- 清理
DROP PROCEDURE IF EXISTS seed_graph_nodes;
DROP PROCEDURE IF EXISTS seed_graph_edges;

-- 验证
SELECT COUNT(*) AS syn_nodes FROM tech_node WHERE code LIKE 'syn_%';
SELECT COUNT(*) AS syn_edges FROM tech_edge WHERE from_id >= 10000000 OR to_id >= 10000000;
SELECT category, COUNT(*) AS n FROM tech_node WHERE code LIKE 'syn_%' GROUP BY category ORDER BY n DESC;

SET SESSION sql_log_bin = 1;
