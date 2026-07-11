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

UPDATE t_user
SET role = 'admin'
WHERE LOWER(email) = '13102373468@163.com' AND role <> 'admin';
