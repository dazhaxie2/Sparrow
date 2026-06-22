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
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_i INT;
    DECLARE v_cat_idx INT;
    DECLARE v_era_idx INT;
    DECLARE v_imp INT;
    DECLARE v_year VARCHAR(32);
    DECLARE v_name VARCHAR(128);
    DECLARE v_summary VARCHAR(512);

    -- 14 个领域(与 01-schema 注释、seed 回填一致)
    -- 用临时表承载,供 RAND() 取模索引
    DROP TEMPORARY TABLE IF EXISTS tmp_cat;
    CREATE TEMPORARY TABLE tmp_cat (idx INT PRIMARY KEY, name VARCHAR(32));
    INSERT INTO tmp_cat VALUES
        (0,'能源动力'),(1,'材料冶金'),(2,'农业食品'),(3,'交通运输'),
        (4,'信息计算'),(5,'通信网络'),(6,'电气电子'),(7,'医学生物'),
        (8,'化学化工'),(9,'建筑工程'),(10,'军事技术'),(11,'航天航空'),
        (12,'数学与基础科学'),(13,'制造与机械');

    -- 10 个时代(era_rank 1-10)
    DROP TEMPORARY TABLE IF EXISTS tmp_era;
    CREATE TEMPORARY TABLE tmp_era (era_rank INT PRIMARY KEY, era VARCHAR(32), year_min INT, year_max INT);
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

    -- 幂等:已生成过则跳过
    IF (SELECT COUNT(*) FROM tech_node WHERE code LIKE 'syn_%') >= p_total THEN
        SELECT CONCAT('已存在 ', p_total, ' 条合成节点,跳过') AS msg;
    ELSE
        SET v_done = (SELECT COALESCE(MAX(id), 9999999) FROM tech_node WHERE code LIKE 'syn_%');

        WHILE v_done < 10000000 + p_total - 1 DO
            SET v_i = 0;
            SET @sql = 'INSERT IGNORE INTO tech_node (id, code, name, era, era_rank, year_label, summary, detail, premium, category, importance, source_url) VALUES ';
            SET @vals = '';

            batch_loop: WHILE v_i < p_batch AND v_done < 10000000 + p_total - 1 DO
                SET v_done = v_done + 1;

                -- 领域 + 时代(均匀分布;真实数据各领域占比不均,但合成数据求可控)
                SET v_cat_idx = FLOOR(RAND() * 14);
                SET v_era_idx = 1 + FLOOR(RAND() * 10);

                -- 重要度:幂律分布 —— 多数低重要度,少数高重要度(LOD 取舍用)
                -- RAND()^3 让分布右偏:中位数 ~12.5,前 1% ~97+
                SET v_imp = LEAST(100, FLOOR(POW(RAND(), 3) * 100) + 1);

                -- 年份标签(从时代年份区间随机取,智能时代用整数年)
                SELECT
                    CASE
                        WHEN year_min < 0 THEN CONCAT('约公元前', FLOOR(ABS(year_min) + RAND() * (year_max - year_min)), '年')
                        ELSE CONCAT(FLOOR(year_min + RAND() * (year_max - year_min)), '年')
                    END
                INTO v_year FROM tmp_era WHERE era_rank = v_era_idx;

                -- 名称:领域 + 编号(可读 + 唯一);摘要:简短占位
                SET v_name = CONCAT((SELECT name FROM tmp_cat WHERE idx = v_cat_idx), '·技术节点', v_done);
                SET v_summary = CONCAT((SELECT name FROM tmp_cat WHERE idx = v_cat_idx), '领域的合成技术节点,用于百万级图谱架构验证。');

                SET @vals = CONCAT(@vals,
                    IF(v_i > 0, ',', ''),
                    '(', v_done,
                    ',''', CONCAT('syn_', v_done), '''',
                    ',', QUOTE(v_name),
                    ',', QUOTE((SELECT era FROM tmp_era WHERE era_rank = v_era_idx)),
                    ',', v_era_idx,
                    ',', QUOTE(v_year),
                    ',', QUOTE(v_summary),
                    ',NULL,0',
                    ',', QUOTE((SELECT name FROM tmp_cat WHERE idx = v_cat_idx)),
                    ',', v_imp,
                    ',''', CONCAT('https://example.com/node/', v_done), '''',
                    ')');

                SET v_i = v_i + 1;
            END WHILE batch_loop;

            SET @sql = CONCAT(@sql, @vals);
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
            COMMIT;

            IF v_done % 100000 < p_batch THEN
                SELECT CONCAT('节点进度: ', v_done - 9999999, ' / ', p_total, ' (', ROUND((v_done - 9999999) * 100.0 / p_total, 1), '%)') AS progress;
            END IF;
        END WHILE;

        SELECT CONCAT('完成: 共生成 ', v_done - 9999999, ' 条合成节点') AS msg;
    END IF;

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
    DECLARE v_target_edges INT;
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_i INT;
    DECLARE v_from BIGINT;
    DECLARE v_to BIGINT;
    DECLARE v_attempts INT;
    DECLARE v_hub_count INT;
    DECLARE v_hub_min BIGINT;
    DECLARE v_hub_max BIGINT;

    SET v_target_edges = p_node_total * p_edge_per_node;

    -- 幂等:已生成过则跳过
    IF (SELECT COUNT(*) FROM tech_edge WHERE from_id >= 10000000 OR to_id >= 10000000) >= v_target_edges THEN
        SELECT CONCAT('已存在 ', v_target_edges, ' 条合成边,跳过') AS msg;
        LEAVE proc_label;
    END IF;

    -- 边权重采样表:高重要度节点作为枢纽(被连概率高),模拟真实图的幂律结构。
    -- 幂律分布下 importance>=70 约占 3%,百万级即 ~3 万个,天然有界,无需 LIMIT。
    -- idx_importance_id 覆盖该 ORDER BY 查询。
    DROP TEMPORARY TABLE IF EXISTS tmp_hub;
    CREATE TEMPORARY TABLE tmp_hub (id BIGINT PRIMARY KEY);
    INSERT IGNORE INTO tmp_hub
    SELECT id FROM tech_node
    WHERE code LIKE 'syn_%' AND importance >= 70;
    SET v_hub_count = (SELECT COUNT(*) FROM tmp_hub);
    SET v_hub_min = (SELECT MIN(id) FROM tmp_hub);
    SET v_hub_max = (SELECT MAX(id) FROM tmp_hub);

    WHILE v_done < v_target_edges DO
        SET v_i = 0;
        SET @sql = 'INSERT IGNORE INTO tech_edge (from_id, to_id) VALUES ';
        SET @vals = '';

        batch_loop: WHILE v_i < p_batch AND v_done < v_target_edges DO
            SET v_attempts = 0;

            -- 重试直到找到合法边(不重复、非自环)
            retry_loop: WHILE v_attempts < 5 DO
                -- from 节点:均匀随机
                SET v_from = 10000000 + FLOOR(RAND() * p_node_total);

                -- to 节点:30% 从枢纽池取(模拟枢纽被大量依赖),70% 均匀随机。
                -- 枢纽取样用 PK 范围采样(O(log n))而非 ORDER BY RAND()(O(n) filesort)。
                -- 注:MySQL 临时表不能在同查询引用两次,故分两步取最近 hub id。
                IF RAND() < 0.3 AND v_hub_count > 0 THEN
                    SET @hub_rand = v_hub_min + FLOOR(RAND() * (v_hub_max - v_hub_min + 1));
                    SELECT id INTO v_to FROM tmp_hub WHERE id >= @hub_rand ORDER BY id LIMIT 1;
                    IF v_to IS NULL THEN
                        SELECT id INTO v_to FROM tmp_hub ORDER BY id DESC LIMIT 1;
                    END IF;
                ELSE
                    SET v_to = 10000000 + FLOOR(RAND() * p_node_total);
                END IF;

                IF v_from <> v_to THEN
                    SET @vals = CONCAT(@vals,
                        IF(v_i > 0, ',', ''),
                        '(', v_from, ',', v_to, ')');
                    SET v_i = v_i + 1;
                    SET v_done = v_done + 1;
                    LEAVE retry_loop;
                END IF;

                SET v_attempts = v_attempts + 1;
            END WHILE retry_loop;

            -- 若 5 次重试都失败(自环),跳过这条
            IF v_attempts >= 5 THEN
                SET v_done = v_done + 1;
            END IF;
        END WHILE batch_loop;

        IF v_i > 0 THEN
            SET @sql = CONCAT(@sql, @vals);
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
            COMMIT;
        END IF;

        IF v_done % 500000 < p_batch THEN
            SELECT CONCAT('边进度: ', v_done, ' / ', v_target_edges, ' (', ROUND(v_done * 100.0 / v_target_edges, 1), '%)') AS progress;
        END IF;
    END WHILE;

    SELECT CONCAT('完成: 共生成合成边') AS msg;

    DROP TEMPORARY TABLE IF EXISTS tmp_hub;
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
