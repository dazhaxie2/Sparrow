package com.sparrow.industrychain.application;

import com.sparrow.common.security.AdminGuard;
import com.sparrow.industrychain.infrastructure.client.UserClient;
import com.sparrow.industrychain.infrastructure.config.IndustryChainAiConfig;
import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import com.sparrow.industrychain.infrastructure.config.ModelConfigBroadcaster;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository.AuditRow;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 模型配置管理:列表(脱敏)/测试连接/保存/原子激活 + 审计。
 *
 * <p>激活流程:{@link #activate} 事务内切换 active 标记 → 用新配置构建模型 →
 * {@link ChatModelProvider#swap} 原子替换 → {@link ModelConfigBroadcaster#publishReload} 通知其它实例。
 * 进行中的调研不受影响(启动时已快照旧模型引用)。
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    private final ModelConfigRepository repository;
    private final ChatModelProvider chatModelProvider;
    private final IndustryChainAiConfig aiConfig;
    private final ModelConfigBroadcaster broadcaster;
    private final UserClient userClient;

    public ModelConfigService(ModelConfigRepository repository, ChatModelProvider chatModelProvider,
                              IndustryChainAiConfig aiConfig, ModelConfigBroadcaster broadcaster,
                              UserClient userClient) {
        this.repository = repository;
        this.chatModelProvider = chatModelProvider;
        this.aiConfig = aiConfig;
        this.broadcaster = broadcaster;
        this.userClient = userClient;
    }

    /** 列出全部配置(脱敏)。 */
    public List<ModelConfig> list() {
        return repository.listAll();
    }

    /** 审计记录。 */
    public List<AuditRow> audits(int limit) {
        return repository.listAudits(limit <= 0 || limit > 200 ? 50 : limit);
    }

    /** 当前操作者必须为管理员,否则抛 403。 */
    public void requireAdmin(long operatorId) {
        try {
            ApiResponse<Map<String, Object>> resp = userClient.profile(operatorId);
            if (resp == null || resp.data() == null) {
                throw new BizException(401, "无法识别用户身份");
            }
            Object role = resp.data().get("role");
            AdminGuard.requireAdmin(role == null ? null : role.toString());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(401, "无法识别用户身份: " + e.getMessage());
        }
    }

    /** 测试连接:用给定配置临时建模型发一条 ping。不落库,记录审计。 */
    public TestResult test(TestConfig req, long operatorId) {
        requireAdmin(operatorId);
        if (req.baseUrl() == null || req.baseUrl().isBlank()
                || req.modelName() == null || req.modelName().isBlank()) {
            throw new BizException("base_url 与 model_name 不能为空");
        }
        // apiKey 为空时复用已激活配置的 key(便于只改 model_name 试探)
        String apiKey = req.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = repository.findActiveDecrypted().map(ModelConfig::apiKeyPlain).orElse("");
        }
        if (apiKey.isBlank()) {
            throw new BizException("未提供 API Key 且无激活配置可复用");
        }
        long started = System.currentTimeMillis();
        try {
            ChatModel probe = OpenAiChatModel.builder()
                    .baseUrl(req.baseUrl())
                    .apiKey(apiKey)
                    .modelName(req.modelName())
                    .maxTokens(16)
                    .timeout(Duration.ofSeconds(req.timeoutSeconds() <= 0 ? 30 : req.timeoutSeconds()))
                    .maxRetries(0)
                    .build();
            String reply = probe.chat("ping");
            long latency = System.currentTimeMillis() - started;
            log.info("模型连接测试成功: {} ({}) {}ms", req.baseUrl(), req.modelName(), latency);
            return new TestResult(true, "连接成功,响应耗时 " + latency + "ms",
                    truncate(reply));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("模型连接测试失败: {} ({}) {}", req.baseUrl(), req.modelName(), msg);
            return new TestResult(false, "连接失败: " + truncate(msg), null);
        }
    }

    /** 保存审计。 */
    public void recordTest(long operatorId, TestConfig req, TestResult result) {
        repository.audit(null, req.name(), operatorId, "TEST",
                "模型:" + req.modelName() + " url:" + req.baseUrl() + " → " + result.message(),
                result.ok());
    }

    /** 保存(新增或更新)。apiKey 为空时保留旧 key。 */
    @Transactional
    public long save(SaveConfig req, long operatorId) {
        requireAdmin(operatorId);
        if (!repository.encryptionReady()) {
            throw new BizException("服务器未配置主密钥 sparrow.crypto.model-config-secret,无法保存 API Key");
        }
        validate(req);
        if (req.id() != null) {
            ModelConfig existing = repository.findDecryptedById(req.id())
                    .orElseThrow(() -> new BizException(404, "配置不存在"));
            String encKey = (req.apiKey() == null || req.apiKey().isBlank())
                    ? null // 保留旧 key
                    : repository.encryptApiKey(req.apiKey());
            repository.update(req.id(), req.name(), req.baseUrl(), req.modelName(),
                    encKey, req.maxTokens(), req.timeoutSeconds(), req.maxRetries());
            repository.audit(req.id(), req.name(), operatorId, "SAVE", "更新配置", null);
            return req.id();
        }
        long id = repository.insert(req.name(), req.baseUrl(), req.modelName(),
                repository.encryptApiKey(req.apiKey()), req.maxTokens(),
                req.timeoutSeconds(), req.maxRetries(), operatorId);
        repository.audit(id, req.name(), operatorId, "SAVE", "新增配置", null);
        return id;
    }

    /** 原子激活:切换 active → 建模型 → swap → 广播。 */
    @Transactional
    public void activate(long configId, long operatorId) {
        requireAdmin(operatorId);
        ModelConfig config = repository.findDecryptedById(configId)
                .orElseThrow(() -> new BizException(404, "配置不存在"));
        if (config.apiKeyPlain() == null || config.apiKeyPlain().isBlank()) {
            throw new BizException("该配置缺少 API Key,无法激活");
        }
        // 先建模型(失败则不切换,保证不破坏当前可用模型)
        ChatModel newModel;
        try {
            newModel = aiConfig.buildFrom(config);
        } catch (Exception e) {
            throw new BizException("新配置构建模型失败,未切换: " + e.getMessage());
        }
        repository.setActive(configId);
        chatModelProvider.swap(newModel);
        repository.audit(configId, config.name(), operatorId, "ACTIVATE",
                "激活配置 base:" + config.baseUrl() + " model:" + config.modelName(), null);
        broadcaster.publishReload();
    }

    /**
     * 应用当前数据库里的激活配置(供启动后 + 广播收到时重新构建并 swap)。
     * 失败时回退到环境变量配置,避免广播把所有实例打成"无模型"。
     */
    public void applyActiveConfig() {
        ModelConfig active = repository.findActiveDecrypted().orElse(null);
        if (active == null || active.apiKeyPlain() == null || active.apiKeyPlain().isBlank()) {
            return; // 无激活配置,保持现状
        }
        try {
            ChatModel model = aiConfig.buildFrom(active);
            chatModelProvider.swap(model);
            log.info("已按广播重载激活配置: {} ({})", active.name(), active.modelName());
        } catch (Exception e) {
            log.warn("广播重载激活配置失败,保持当前模型: {}", e.getMessage());
        }
    }

    private void validate(SaveConfig req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("配置名称不能为空");
        }
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw new BizException("base_url 不能为空");
        }
        if (req.modelName() == null || req.modelName().isBlank()) {
            throw new BizException("model_name 不能为空");
        }
        if (req.id() == null && (req.apiKey() == null || req.apiKey().isBlank())) {
            throw new BizException("新增配置必须提供 API Key");
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    public record TestConfig(String name, String baseUrl, String modelName, String apiKey,
                             int timeoutSeconds) {
    }

    public record SaveConfig(Long id, String name, String baseUrl, String modelName,
                             String apiKey, int maxTokens, int timeoutSeconds, int maxRetries) {
    }

    public record TestResult(boolean ok, String message, String reply) {
    }
}
