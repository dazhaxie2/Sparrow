package com.sparrow.user.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * t_user 平滑升级：为既有库补 email / role 列,并在无管理员时把最小 id 用户提为 admin。
 *
 * <p>schema.sql 仅对全新库生效;线上既有库需靠本迁移器幂等补列。
 */
@Component
public class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;

    @Autowired
    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("t_user", "email", "VARCHAR(128) NULL AFTER password_hash");
        addColumnIfMissing("t_user", "role", "VARCHAR(16) NOT NULL DEFAULT 'user' AFTER email");
        addUniqueIndexIfMissing("t_user", "uk_email", "email");
        promoteFirstUserAsAdminIfNone();
    }

    /** 幂等加列：列已存在时忽略异常。 */
    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("迁移: {} 新增列 {}", table, column);
        } catch (Exception ignored) {
            // 列已存在
        }
    }

    /** 幂等加唯一索引：索引已存在时忽略异常。 */
    private void addUniqueIndexIfMissing(String table, String indexName, String column) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD UNIQUE KEY " + indexName + " (" + column + ")");
            log.info("迁移: {} 新增唯一索引 {}", table, indexName);
        } catch (Exception ignored) {
            // 索引已存在
        }
    }

    /** 既有库无 admin 时,把最小 id 用户提为 admin(让管理端可立即使用)。 */
    private void promoteFirstUserAsAdminIfNone() {
        try {
            Integer adminCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_user WHERE role = 'admin'", Integer.class);
            if (adminCount != null && adminCount > 0) {
                return;
            }
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM t_user", Integer.class);
            if (total == null || total == 0) {
                return;
            }
            int updated = jdbc.update(
                    "UPDATE t_user SET role = 'admin' WHERE id = (SELECT min_id FROM "
                            + "(SELECT MIN(id) AS min_id FROM t_user) t)");
            if (updated > 0) {
                log.warn("迁移: 未发现管理员,已将最小 id 用户提为 admin(共 {} 个用户)", total);
            }
        } catch (Exception e) {
            log.warn("迁移 promoteFirstUserAsAdminIfNone 失败(可忽略): {}", e.getMessage());
        }
    }
}
