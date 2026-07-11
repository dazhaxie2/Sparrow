-- Sparrow user identity additive migration.
-- Safe to run repeatedly. It never creates or resets a password/token.
USE sparrow_user;

SET @has_email := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'email'
);
SET @ddl := IF(@has_email = 0,
    'ALTER TABLE t_user ADD COLUMN email VARCHAR(128) NULL AFTER password_hash',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_role := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'role'
);
SET @ddl := IF(@has_role = 0,
    'ALTER TABLE t_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT ''user'' AFTER email',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_email_index := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND INDEX_NAME = 'uk_email'
);
SET @ddl := IF(@has_email_index = 0,
    'ALTER TABLE t_user ADD UNIQUE KEY uk_email (email)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 管理员角色提升由 sparrow-user 启动时读取 SPARROW_ADMIN_EMAIL 后参数化执行。
-- 本迁移脚本不保存、接收或打印管理员邮箱。
