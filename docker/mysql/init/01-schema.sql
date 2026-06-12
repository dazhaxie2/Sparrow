-- Sparrow Phase 1 schema(单库,本地事务)
SET NAMES utf8mb4;

CREATE TABLE t_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    username         VARCHAR(64)  NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    member_expire_at DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE t_order (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(32) NOT NULL,
    user_id      BIGINT      NOT NULL,
    product_code VARCHAR(32) NOT NULL,
    product_name VARCHAR(64) NOT NULL,
    amount_cent  INT         NOT NULL,
    status       VARCHAR(16) NOT NULL, -- CREATED / PAID / CLOSED
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at      DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE tech_node (
    id         BIGINT       NOT NULL,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    era        VARCHAR(32)  NOT NULL,
    era_rank   INT          NOT NULL,
    year_label VARCHAR(32)  NULL,
    summary    VARCHAR(512) NOT NULL,
    detail     TEXT         NULL,
    premium    TINYINT      NOT NULL DEFAULT 0, -- 1=详情为会员专属深度内容
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_era (era_rank)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE tech_edge (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    from_id BIGINT NOT NULL, -- 前置技术
    to_id   BIGINT NOT NULL, -- 后继技术
    PRIMARY KEY (id),
    UNIQUE KEY uk_edge (from_id, to_id),
    KEY idx_to (to_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
