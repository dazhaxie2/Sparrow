-- AI 聊天历史功能:为 sparrow_ai 库补建 chat_session / chat_message 两张表。
-- 幂等:可重复执行(IF NOT EXISTS)。新部署由 01-schema.sql 建表,本文件供存量库升级。

SET NAMES utf8mb4;
USE sparrow_ai;

CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(120) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_session_user (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    mode       VARCHAR(16) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_message_session (session_id, id),
    KEY idx_chat_message_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
