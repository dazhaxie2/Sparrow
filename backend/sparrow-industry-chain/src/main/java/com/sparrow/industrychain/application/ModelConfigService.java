package com.sparrow.industrychain.application;

import com.sparrow.common.security.AdminGuard;
import com.sparrow.industrychain.infrastructure.client.UserClient;
import com.sparrow.industrychain.infrastructure.config.IndustryChainAiConfig;
import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository.AuditRow;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型配置管理:列表(脱敏)/测试连接/保存/原子激活 + 审计。
 *
 * <p>激活流程:{@link #activate} 事务内切换 active 标记 → 写审计 → 提交后用新配置构建模型并
 * {@link ChatModelProvider#swap} 原子替换。当前为单实例部署,本机 swap 即完成热切换;
 * 若将来多实例,可在此处接入广播(Nacos 配置写回或 Redis Pub/Sub)。
 * 进行中的调研不受影响(启动时已快照旧模型引用)。
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    private final ModelConfigRepository repository;
    private final ChatModelProvider chatModelProvider;
    private final IndustryChainAiConfig aiConfig;
    private final UserClient userClient;

    public ModelConfigService(ModelConfigRepository repository, ChatModelProvider chatModelProvider,
                              IndustryChainAiConfig aiConfig, UserClient userClient) {
        this.repository = repository;
        this.chatModelProvider = chatModelProvider;
        this.aiConfig = aiConfig;
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
            AdminGuard.requireAdmin(AdminGuard.roleOf(resp));
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
        normalizeBaseUrl(req.baseUrl());
        // apiKey 为空时复用已激活配置的 key(便于只改 model_name 试探)
        String apiKey = req.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            ModelConfig active = repository.findActiveDecrypted()
                    .orElseThrow(() -> new BizException("未提供 API Key 且无激活配置可复用"));
            if (!sameBaseUrl(req.baseUrl(), active.baseUrl())) {
                throw new BizException("Base URL 已变化，必须重新提供 API Key");
            }
            apiKey = active.apiKeyPlain();
        }
        if (apiKey == null || apiKey.isBlank()) {
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
                    .customParameters(IndustryChainAiConfig.providerCustomParameters(
                            req.baseUrl(), req.modelName()))
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
        repository.audit(null, req.name(), operatorId, AuditAction.TEST,
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
            if ((req.apiKey() == null || req.apiKey().isBlank())
                    && !sameBaseUrl(req.baseUrl(), existing.baseUrl())) {
                throw new BizException("Base URL 已变化，必须重新提供 API Key");
            }
            String encKey = (req.apiKey() == null || req.apiKey().isBlank())
                    ? null // 保留旧 key
                    : repository.encryptApiKey(req.apiKey());
            repository.update(req.id(), req.name(), req.baseUrl(), req.modelName(),
                    encKey, req.maxTokens(), req.timeoutSeconds(), req.maxRetries());
            repository.audit(req.id(), req.name(), operatorId, AuditAction.SAVE, "更新配置", null);
            return req.id();
        }
        long id = repository.insert(req.name(), req.baseUrl(), req.modelName(),
                repository.encryptApiKey(req.apiKey()), req.maxTokens(),
                req.timeoutSeconds(), req.maxRetries(), operatorId);
        repository.audit(id, req.name(), operatorId, AuditAction.SAVE, "新增配置", null);
        return id;
    }

    /**
     * 原子激活:DB 切换 active + 审计在事务内;内存 swap + Redis 广播延迟到事务提交后。
     *
     * <p>为何 swap 必须在 afterCommit:它是不可回滚的内存状态变更。若放在事务体内且 DB 提交失败
     * 回滚,内存里的模型已切但 DB active 标记回滚,状态不一致。放 afterCommit 则只有 DB 真正提交
     * 成功后才切换。
     *
     * <p>测试环境无真实事务时(直接 new Service),isSynchronizationActive() 为 false,
     * 回退为立即执行,保证行为可测。
     */
    @Transactional
    public void activate(long configId, long operatorId) {
        requireAdmin(operatorId);
        ModelConfig config = repository.findDecryptedById(configId)
                .orElseThrow(() -> new BizException(404, "配置不存在"));
        if (config.apiKeyPlain() == null || config.apiKeyPlain().isBlank()) {
            throw new BizException("该配置缺少 API Key,无法激活");
        }
        // 先建模型(失败则不切换,保证不破坏当前可用模型)。模型对象在 afterCommit 时使用。
        ChatModel newModel;
        StreamingChatModel streamingModel;
        try {
            newModel = aiConfig.buildFrom(config);
            streamingModel = aiConfig.buildStreamingFrom(config);
        } catch (Exception e) {
            throw new BizException("新配置构建模型失败,未切换: " + e.getMessage());
        }
        repository.setActive(configId);
        repository.audit(configId, config.name(), operatorId, AuditAction.ACTIVATE,
                "激活配置 base:" + config.baseUrl() + " model:" + config.modelName(), null);
        // 内存 swap 放事务提交后,避免 DB 回滚导致内存/DB 状态分裂
        Runnable afterCommit = () -> {
            chatModelProvider.swap(newModel);
            chatModelProvider.swapStreaming(streamingModel);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCommit.run();
                }
            });
        } else {
            // 无事务上下文(如单测直接调用):立即执行
            afterCommit.run();
        }
    }

    private void validate(SaveConfig req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("配置名称不能为空");
        }
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw new BizException("base_url 不能为空");
        }
        normalizeBaseUrl(req.baseUrl());
        if (req.modelName() == null || req.modelName().isBlank()) {
            throw new BizException("model_name 不能为空");
        }
        if (req.id() == null && (req.apiKey() == null || req.apiKey().isBlank())) {
            throw new BizException("新增配置必须提供 API Key");
        }
    }

    private static boolean sameBaseUrl(String left, String right) {
        return normalizeBaseUrl(left).equals(normalizeBaseUrl(right));
    }

    /** Canonicalize the complete provider endpoint; credentials may only be reused for this exact endpoint. */
    private static String normalizeBaseUrl(String value) {
        try {
            URI parsed = URI.create(value.trim()).normalize();
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || host.isBlank()
                    || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
                throw new IllegalArgumentException("unsupported endpoint");
            }
            int port = parsed.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
                port = -1;
            }
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            if ("/".equals(path)) {
                path = "";
            }
            return new URI(scheme, null, host, port, path, null, null).toASCIIString();
        } catch (Exception error) {
            throw new BizException("base_url 格式不正确");
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 审计动作类型常量(避免裸字符串散落,前后端约定)。 */
    public static final class AuditAction {
        public static final String TEST = "TEST";
        public static final String SAVE = "SAVE";
        public static final String ACTIVATE = "ACTIVATE";
        private AuditAction() {
        }
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
