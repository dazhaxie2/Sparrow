package com.sparrow.user.infrastructure.event;

import com.sparrow.common.event.OrderPaidEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class MemberGrantLogRepository {

    private final JdbcTemplate jdbc;

    public MemberGrantLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条会员开通流水。order_no 为唯一键,重复事件/重复支付回调只落一条,返回 false。
     */
    public boolean recordIfAbsent(OrderPaidEvent event) {
        ensureTable();
        try {
            jdbc.update("INSERT INTO member_grant_log "
                            + "(order_no, user_id, product_code, member_days, amount_cent, event_id, paid_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    event.orderNo(), event.userId(), event.productCode(), event.memberDays(),
                    event.amountCent(), event.eventId(),
                    event.paidAt() == null ? null : Timestamp.from(event.paidAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void ensureTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS member_grant_log ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "order_no VARCHAR(32) NOT NULL,"
                + "user_id BIGINT NOT NULL,"
                + "product_code VARCHAR(32) NOT NULL,"
                + "member_days INT NOT NULL,"
                + "amount_cent INT NOT NULL,"
                + "event_id VARCHAR(64) NOT NULL,"
                + "paid_at DATETIME NULL,"
                + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_order_no (order_no),"
                + "KEY idx_user (user_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
