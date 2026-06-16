-- Sparrow Phase 2 压测数据生成:1000 万用户
-- 用法:docker exec -i sparrow-mysql mysql -uroot -proot123 sparrow_user < backend/scripts/seed-users.sql
-- 或在 MySQL 容器内:USE sparrow_user; SOURCE /path/to/seed-users.sql;
--
-- 生成策略:
--   - 用户名格式:loaduser_00000001 ~ loaduser_10000000
--   - 密码统一:$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy (明文 = "test123456")
--   - 会员分布:~10% 有效会员,~5% 过期会员,~85% 非会员
--   - 批量插入,每批 5000 行,自动提交

-- ⚠️ 执行前先关闭 binlog,避免大批量写入产生巨量 binlog
SET SESSION sql_log_bin = 0;

-- 创建临时存储过程
DROP PROCEDURE IF EXISTS seed_users;
DELIMITER $$
CREATE PROCEDURE seed_users(
    IN start_id INT,
    IN total_count INT,
    IN batch_size INT
)
BEGIN
    DECLARE i INT DEFAULT 0;
    DECLARE done INT DEFAULT 0;
    DECLARE member_ratio INT;
    DECLARE expire_ratio INT;

    SET done = start_id;

    -- 自动判断是否已生成过(幂等)
    IF (SELECT COUNT(*) FROM t_user WHERE username LIKE 'loaduser_%') >= total_count THEN
        SELECT CONCAT('已存在 ', total_count, ' 条压测用户,跳过') AS msg;
    ELSE
        SET done = (SELECT COALESCE(MAX(id), 0) FROM t_user WHERE username LIKE 'loaduser_%');
        IF done < start_id THEN SET done = start_id - 1; END IF;

        WHILE done < start_id + total_count - 1 DO
            SET i = 0;

            -- 批量构建 INSERT
            SET @sql = 'INSERT IGNORE INTO t_user (username, password_hash, member_expire_at, created_at) VALUES ';
            SET @vals = '';

            batch_loop: WHILE i < batch_size AND done < start_id + total_count - 1 DO
                SET done = done + 1;

                -- 15% 概率是会员
                SET member_ratio = FLOOR(1 + RAND() * 100);
                IF member_ratio <= 10 THEN
                    -- 有效会员:未来 1-365 天到期
                    SET @expire = DATE_ADD(NOW(), INTERVAL FLOOR(1 + RAND() * 365) DAY);
                    SET @vals = CONCAT(@vals,
                        IF(i > 0, ',', ''),
                        '(CONCAT(''loaduser_'', LPAD(', done, ', 8, ''0'')),',
                        '''$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'',',
                        '''', @expire, ''',',
                        'DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 730) DAY))');
                ELSEIF member_ratio <= 15 THEN
                    -- 过期会员:过去 1-365 天到期
                    SET @expire = DATE_SUB(NOW(), INTERVAL FLOOR(1 + RAND() * 365) DAY);
                    SET @vals = CONCAT(@vals,
                        IF(i > 0, ',', ''),
                        '(CONCAT(''loaduser_'', LPAD(', done, ', 8, ''0'')),',
                        '''$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'',',
                        '''', @expire, ''',',
                        'DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 730) DAY))');
                ELSE
                    -- 非会员
                    SET @vals = CONCAT(@vals,
                        IF(i > 0, ',', ''),
                        '(CONCAT(''loaduser_'', LPAD(', done, ', 8, ''0'')),',
                        '''$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'',',
                        'NULL,',
                        'DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 730) DAY))');
                END IF;

                SET i = i + 1;
            END WHILE batch_loop;

            SET @sql = CONCAT(@sql, @vals);

            SET @sql_done = done;
            SET @sql_total = start_id + total_count - 1;
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
            COMMIT;

            -- 进度报告(每 50 万条输出一次)
            IF done % 500000 < batch_size THEN
                SELECT CONCAT('进度: ', done, ' / ', @sql_total, ' (', ROUND(done * 100.0 / @sql_total, 1), '%)') AS progress;
            END IF;
        END WHILE;

        SELECT CONCAT('完成: 共生成 ', done - start_id + 1, ' 条压测用户') AS msg;
    END IF;
END$$
DELIMITER ;

-- 调用:生成 1000 万用户(ID 从 100 开始,避开测试用户 1-99)
-- 预计耗时:5-15 分钟(取决于硬件)
CALL seed_users(100, 10000000, 5000);

-- 清理存储过程
DROP PROCEDURE IF EXISTS seed_users;

-- 验证
SELECT COUNT(*) AS total_users FROM t_user;
SELECT COUNT(*) AS load_users FROM t_user WHERE username LIKE 'loaduser_%';
SELECT
    COUNT(CASE WHEN member_expire_at > NOW() THEN 1 END) AS active_members,
    COUNT(CASE WHEN member_expire_at <= NOW() AND member_expire_at IS NOT NULL THEN 1 END) AS expired_members,
    COUNT(CASE WHEN member_expire_at IS NULL THEN 1 END) AS non_members
FROM t_user WHERE username LIKE 'loaduser_%';

SET SESSION sql_log_bin = 1;
