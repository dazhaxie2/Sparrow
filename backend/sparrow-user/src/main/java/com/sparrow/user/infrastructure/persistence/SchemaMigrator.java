package com.sparrow.user.infrastructure.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Additive, idempotent upgrades for identity and administrator bootstrap data. */
@Component
public class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcTemplate jdbc;
    private final String bootstrapAdminEmail;

    public SchemaMigrator(JdbcTemplate jdbc,
                          @Value("${sparrow.auth.bootstrap-admin-email:13102373468@163.com}")
                          String bootstrapAdminEmail) {
        this.jdbc = jdbc;
        this.bootstrapAdminEmail = bootstrapAdminEmail == null
                ? "" : bootstrapAdminEmail.trim().toLowerCase(Locale.ROOT);
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("t_user", "email", "VARCHAR(128) NULL AFTER password_hash");
        addColumnIfMissing("t_user", "role", "VARCHAR(16) NOT NULL DEFAULT 'user' AFTER email");
        addUniqueIndexIfMissing("t_user", "uk_email", "email");
        promoteConfiguredAdministrator();
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("Schema migration added {}.{}", table, column);
        } catch (Exception ignored) {
            // Existing column is the expected idempotent path.
        }
    }

    private void addUniqueIndexIfMissing(String table, String indexName, String column) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD UNIQUE KEY " + indexName + " (" + column + ")");
            log.info("Schema migration added index {} on {}", indexName, table);
        } catch (Exception ignored) {
            // Existing index is the expected idempotent path.
        }
    }

    private void promoteConfiguredAdministrator() {
        if (bootstrapAdminEmail.isBlank()) {
            return;
        }
        try {
            int updated = jdbc.update(
                    "UPDATE t_user SET role='admin' WHERE LOWER(email)=? AND role<>'admin'",
                    bootstrapAdminEmail);
            if (updated > 0) {
                log.info("Promoted configured administrator account by verified email");
            }
        } catch (Exception error) {
            log.warn("Configured administrator promotion was not applied: {}", error.getMessage());
        }
    }
}
