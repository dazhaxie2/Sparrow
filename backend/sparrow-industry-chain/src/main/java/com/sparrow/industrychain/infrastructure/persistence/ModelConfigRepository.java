package com.sparrow.industrychain.infrastructure.persistence;

import com.sparrow.common.crypto.AesGcmCrypto;
import com.sparrow.common.ai.model.ModelKind;
import com.sparrow.common.ai.model.ModelScene;
import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 模型配置仓储:CRUD + 当前激活配置查询 + 审计写入。
 *
 * <p>API Key 以 AES-GCM 密文入库;主密钥从环境变量 {@code sparrow.crypto.model-config-secret}
 * 注入(base64,32 字节)。返回视图前先解密再脱敏,保证永不回传明文。
 */
@Repository
public class ModelConfigRepository {

    private final JdbcTemplate jdbc;
    private final byte[] masterKey;

    public ModelConfigRepository(JdbcTemplate jdbc,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${sparrow.crypto.model-config-secret:}") String secretB64) {
        this.jdbc = jdbc;
        this.masterKey = resolveKey(secretB64);
    }

    private static byte[] resolveKey(String secretB64) {
        if (secretB64 == null || secretB64.isBlank()) {
            // 未配置主密钥:加密功能不可用,但模块仍可启动(由 Service 层拦截管理端写入)。
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(secretB64);
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("主密钥长度需为 16/24/32 字节(AES-128/192/256)");
            }
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("sparrow.crypto.model-config-secret 解析失败: " + e.getMessage(), e);
        }
    }

    /** 主密钥是否已配置(决定管理端能否写入/切换)。 */
    public boolean encryptionReady() {
        return masterKey != null;
    }

    /** 加密明文 API Key。 */
    public String encryptApiKey(String plain) {
        requireKey();
        return AesGcmCrypto.encrypt(plain, masterKey);
    }

    /** 解密 API Key,解密失败返回空串(避免影响整个建模型流程)。 */
    public String decryptApiKey(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isBlank()) {
            return "";
        }
        requireKey();
        try {
            return AesGcmCrypto.decrypt(cipherBase64, masterKey);
        } catch (Exception e) {
            return "";
        }
    }

    private void requireKey() {
        if (masterKey == null) {
            throw new IllegalStateException("未配置主密钥 sparrow.crypto.model-config-secret");
        }
    }

    /** 列出全部配置(脱敏视图,按 id 升序)。 */
    public List<ModelConfig> listAll() {
        return jdbc.query("SELECT id,name,base_url,model_name,api_key_encrypted,"
                + "max_tokens,timeout_seconds,max_retries,active,scene,model_kind,created_by,created_at,updated_at "
                + "FROM model_config ORDER BY id", (rs, i) -> ModelConfig.forView(
                rs.getLong("id"), rs.getString("name"), rs.getString("base_url"),
                rs.getString("model_name"), ModelConfig.mask(decryptApiKey(rs.getString("api_key_encrypted"))),
                rs.getInt("max_tokens"), rs.getInt("timeout_seconds"), rs.getInt("max_retries"),
                rs.getInt("active") == 1,
                requireScene(rs.getString("scene")),
                requireKind(rs.getString("model_kind")),
                rs.getObject("created_by", Long.class),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    /** 查询单条配置(明文解密版,仅建模型时用)。 */
    public Optional<ModelConfig> findDecryptedById(long id) {
        List<ModelConfig> list = jdbc.query("SELECT id,name,base_url,model_name,api_key_encrypted,"
                + "max_tokens,timeout_seconds,max_retries,active,scene,model_kind FROM model_config WHERE id=?",
                (rs, i) -> ModelConfig.decrypted(rs.getLong("id"), rs.getString("name"),
                        rs.getString("base_url"), rs.getString("model_name"),
                        decryptApiKey(rs.getString("api_key_encrypted")),
                        rs.getInt("max_tokens"), rs.getInt("timeout_seconds"),
                        rs.getInt("max_retries"), rs.getInt("active") == 1,
                        requireScene(rs.getString("scene")),
                        requireKind(rs.getString("model_kind"))), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 指定场景当前激活的配置(明文解密版,用于构建模型)。 */
    public Optional<ModelConfig> findActiveDecrypted(ModelScene scene) {
        List<ModelConfig> list = jdbc.query("SELECT id,name,base_url,model_name,api_key_encrypted,"
                + "max_tokens,timeout_seconds,max_retries,active,scene,model_kind "
                + "FROM model_config WHERE active=1 AND scene=? LIMIT 1",
                (rs, i) -> ModelConfig.decrypted(rs.getLong("id"), rs.getString("name"),
                        rs.getString("base_url"), rs.getString("model_name"),
                        decryptApiKey(rs.getString("api_key_encrypted")),
                        rs.getInt("max_tokens"), rs.getInt("timeout_seconds"),
                        rs.getInt("max_retries"), rs.getInt("active") == 1,
                        requireScene(rs.getString("scene")),
                        requireKind(rs.getString("model_kind"))), scene.dbValue());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private static ModelScene requireScene(String value) {
        ModelScene scene = ModelScene.fromDbValue(value);
        if (scene == null) {
            throw new IllegalStateException("model_config.scene 无效: " + value);
        }
        return scene;
    }

    private static ModelKind requireKind(String value) {
        ModelKind kind = ModelKind.fromDbValue(value);
        if (kind == null) {
            throw new IllegalStateException("model_config.model_kind 无效: " + value);
        }
        return kind;
    }

    /** 统计配置总数。 */
    public int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM model_config", Integer.class);
        return c == null ? 0 : c;
    }

    /** 新增配置,返回自增 id。 */
    public long insert(String name, String baseUrl, String modelName, String apiKeyEncrypted,
                       int maxTokens, int timeoutSeconds, int maxRetries, Long createdBy,
                       ModelScene scene, ModelKind kind) {
        return insertWithActive(name, baseUrl, modelName, apiKeyEncrypted,
                maxTokens, timeoutSeconds, maxRetries, createdBy, false, scene, kind);
    }

    /**
     * 新增配置并立即激活(用于环境变量引导:首次启动把 env 配置导入为 active 记录)。
     * created_by 传 null,因为引导发生在无人交互的启动阶段。
     */
    public long insertActive(String name, String baseUrl, String modelName, String apiKeyEncrypted,
                             int maxTokens, int timeoutSeconds, int maxRetries,
                             ModelScene scene, ModelKind kind) {
        long id = insertWithActive(name, baseUrl, modelName, apiKeyEncrypted,
                maxTokens, timeoutSeconds, maxRetries, null, false, scene, kind);
        // 引导记录直接置为 active(此时该场景无 active 配置,setActiveByScene 不会误伤其它场景)
        setActiveByScene(id, scene);
        return id;
    }

    private long insertWithActive(String name, String baseUrl, String modelName, String apiKeyEncrypted,
                                  int maxTokens, int timeoutSeconds, int maxRetries,
                                  Long createdBy, boolean active, ModelScene scene, ModelKind kind) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO model_config(name,base_url,model_name,api_key_encrypted,"
                            + "max_tokens,timeout_seconds,max_retries,active,scene,model_kind,created_by) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, baseUrl);
            ps.setString(3, modelName);
            ps.setString(4, apiKeyEncrypted);
            ps.setInt(5, maxTokens);
            ps.setInt(6, timeoutSeconds);
            ps.setInt(7, maxRetries);
            ps.setInt(8, active ? 1 : 0);
            ps.setString(9, scene.dbValue());
            ps.setString(10, kind.dbValue());
            ps.setObject(11, createdBy);
            return ps;
        }, key);
        return key.getKey() == null ? 0L : key.getKey().longValue();
    }

    /** 更新配置。apiKeyPlain 为 null/空时保留旧密钥。 */
    public void update(long id, String name, String baseUrl, String modelName,
                       String apiKeyEncryptedOrNull, int maxTokens, int timeoutSeconds, int maxRetries,
                       ModelScene scene, ModelKind kind) {
        if (apiKeyEncryptedOrNull != null) {
            jdbc.update("UPDATE model_config SET name=?,base_url=?,model_name=?,api_key_encrypted=?,"
                    + "max_tokens=?,timeout_seconds=?,max_retries=?,scene=?,model_kind=? WHERE id=?",
                    name, baseUrl, modelName, apiKeyEncryptedOrNull, maxTokens, timeoutSeconds, maxRetries,
                    scene.dbValue(), kind.dbValue(), id);
        } else {
            jdbc.update("UPDATE model_config SET name=?,base_url=?,model_name=?,"
                    + "max_tokens=?,timeout_seconds=?,max_retries=?,scene=?,model_kind=? WHERE id=?",
                    name, baseUrl, modelName, maxTokens, timeoutSeconds, maxRetries,
                    scene.dbValue(), kind.dbValue(), id);
        }
    }

    /**
     * 事务内按场景原子切换激活:该场景全部置 0,目标置 1。
     * 只影响目标场景的 active 标记,不误伤其它场景(模型池按场景独立激活)。
     */
    public void setActiveByScene(long configId, ModelScene scene) {
        jdbc.update("UPDATE model_config SET active=0 WHERE scene=?", scene.dbValue());
        int updated = jdbc.update("UPDATE model_config SET active=1 WHERE id=? AND scene=?",
                configId, scene.dbValue());
        if (updated != 1) {
            throw new IllegalStateException("目标模型配置不存在或场景已变化");
        }
    }

    /** 写审计日志。 */
    public void audit(Long configId, String configName, Long operatorId,
                      String action, String summary, Boolean testOk) {
        jdbc.update("INSERT INTO model_config_audit(config_id,config_name,operator_id,action,summary,test_ok) "
                + "VALUES(?,?,?,?,?,?)", configId, configName, operatorId, action, summary,
                testOk == null ? null : (testOk ? 1 : 0));
    }

    /** 审计列表(最近 limit 条)。 */
    public List<AuditRow> listAudits(int limit) {
        return jdbc.query("SELECT id,config_id,config_name,operator_id,action,summary,test_ok,created_at "
                + "FROM model_config_audit ORDER BY id DESC LIMIT ?", (rs, i) -> new AuditRow(
                rs.getLong("id"), rs.getObject("config_id", Long.class), rs.getString("config_name"),
                rs.getObject("operator_id", Long.class), rs.getString("action"), rs.getString("summary"),
                rs.getObject("test_ok", Integer.class),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime()),
                limit);
    }

    public record AuditRow(Long id, Long configId, String configName, Long operatorId,
                           String action, String summary, Integer testOk, LocalDateTime createdAt) {
    }
}
