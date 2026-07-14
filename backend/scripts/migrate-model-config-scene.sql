-- 模型配置场景化(模型池):为 model_config 表补 scene/model_kind 列。
-- 幂等:列已存在时存储过程判断跳过,重复执行不报错。
-- 全新库无需此文件 —— IndustryChainRepository.ensureTables() 建表时已包含这两列。
SET NAMES utf8mb4;
USE sparrow_industry_chain;

-- scene:模型配置所属场景(模型池按场景激活,每个场景最多一条 active=1)。
-- 取值见 sparrow-common 的 ModelScene 枚举:
--   sparrow_ai_chat / sparrow_ai_embedding / chain_planning /
--   chain_extraction / chain_report / chain_agent_stream
SET @has_scene := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_config' AND COLUMN_NAME = 'scene'
);
SET @ddl := IF(@has_scene = 0,
    'ALTER TABLE model_config ADD COLUMN scene VARCHAR(48) NOT NULL DEFAULT ''chain_planning'' AFTER active',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- model_kind:模型类型,决定消费方构建 OpenAiChatModel 还是 OpenAiEmbeddingModel。
SET @has_kind := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_config' AND COLUMN_NAME = 'model_kind'
);
SET @ddl := IF(@has_kind = 0,
    'ALTER TABLE model_config ADD COLUMN model_kind VARCHAR(16) NOT NULL DEFAULT ''chat'' AFTER scene',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 现有数据迁移:全部归入 chain_planning 场景、chat 类型(与升级前行为一致)。
-- active=1 的记录保持激活,成为 chain_planning 场景的初始激活配置。
UPDATE model_config SET scene = 'chain_planning', model_kind = 'chat'
WHERE scene = '' OR scene IS NULL OR model_kind = '' OR model_kind IS NULL;
