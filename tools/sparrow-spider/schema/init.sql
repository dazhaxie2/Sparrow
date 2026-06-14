-- SparrowSpider 自有库表(与 Sparrow 业务库隔离)
CREATE DATABASE IF NOT EXISTS techspider DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE techspider;

-- 候选技术词条:发现阶段写入,贯穿全流程的状态机
-- status: PENDING(待爬) → CRAWLED(已爬) → EXTRACTED(已抽取) | FAILED
CREATE TABLE IF NOT EXISTS tech_candidate (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    term        VARCHAR(128) NOT NULL,            -- 检索词
    page_title  VARCHAR(256) NULL,                -- 解析到的词条标题
    page_id     BIGINT       NULL,                -- MediaWiki pageid
    source      VARCHAR(32)  NOT NULL,            -- seed / link / category
    sparrow_code VARCHAR(64) NULL,                -- 对应 Sparrow 内置节点 code(种子对齐用)
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    era         VARCHAR(32)  NULL,
    era_rank    INT          NULL,
    year_label  VARCHAR(64)  NULL,
    summary     VARCHAR(1024) NULL,
    detail      TEXT         NULL,
    category    VARCHAR(32)  NULL,                -- 领域(发现阶段的类目提示 / 抽取阶段 LLM 精修)
    importance  INT          NOT NULL DEFAULT 0,  -- 重要度 = 被其它已爬词条链接的次数(入链中心度)
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_term (term),
    KEY idx_status (status),
    KEY idx_importance (importance)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 原始页面:爬取阶段的不可变产物(可重放抽取)
CREATE TABLE IF NOT EXISTS raw_page (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    candidate_id BIGINT        NOT NULL,
    page_id      BIGINT        NULL,
    title        VARCHAR(256)  NOT NULL,
    url          VARCHAR(512)  NULL,
    extract_text MEDIUMTEXT    NULL,               -- 词条纯文本
    links_json   MEDIUMTEXT    NULL,               -- 词条内链(候选发现用)
    fetched_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_candidate (candidate_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 技术依赖关系:LLM 抽取产物,按名称暂存,导出时解析为节点 id
CREATE TABLE IF NOT EXISTS tech_relation (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    from_name  VARCHAR(160) NOT NULL,   -- 前置/源词条名
    to_name    VARCHAR(160) NOT NULL,   -- 后继/目标词条名
    kind       VARCHAR(16)  NOT NULL DEFAULT 'llm',  -- llm(有向前置) / structural(内链共现,方向由 era_rank 定)
    confidence VARCHAR(16)  NOT NULL DEFAULT 'llm',  -- llm / manual
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rel (from_name, to_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
