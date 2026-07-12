package com.sparrow.industrychain.infrastructure.config;

import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 产业链调研专用 LLM 配置。
 *
 * <p>启动时(应用就绪后)构建初始 {@link ChatModel} 并注入 {@link ChatModelProvider}。
 * 优先级:数据库激活的 model_config > 环境变量/Nacos 配置(sparrow.industry-chain.*)。
 * 管理端切换配置后由 {@code ModelConfigService} 直接调用 {@code ChatModelProvider.swap()},无需重启。
 */
@Configuration
public class IndustryChainAiConfig {

    private static final Logger log = LoggerFactory.getLogger(IndustryChainAiConfig.class);

    private final ChatModelProvider chatModelProvider;
    private final ModelConfigRepository modelConfigRepository;
    private final IndustryChainProperties props;

    public IndustryChainAiConfig(ChatModelProvider chatModelProvider,
                                 ModelConfigRepository modelConfigRepository,
                                 IndustryChainProperties props) {
        this.chatModelProvider = chatModelProvider;
        this.modelConfigRepository = modelConfigRepository;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initModel() {
        // 1) 数据库已有激活配置(管理端配过):用它建模型
        ModelConfig active = modelConfigRepository.findActiveDecrypted().orElse(null);
        if (active != null && active.apiKeyPlain() != null && !active.apiKeyPlain().isBlank()) {
            try {
                chatModelProvider.init(buildFrom(active));
                chatModelProvider.initStreaming(buildStreamingFrom(active));
                log.info("LLM 初始化自数据库激活配置: {} ({})", active.name(), active.modelName());
                return;
            } catch (Exception e) {
                log.warn("数据库激活配置建模型失败,回退到环境变量: {}", e.getMessage());
            }
        }
        // 2) 数据库为空且环境变量已配置:把 env 配置作为初始记录导入(bootstrap)。
        //    真实企业做法:env 是一次性引导源,导入后 DB 成为唯一事实来源,管理端可见可改可审计。
        //    仅在表空时导入,保证重启幂等,不会与管理员后续手动配置冲突。
        if (props.llmConfigured() && modelConfigRepository.count() == 0
                && modelConfigRepository.encryptionReady()) {
            long seeded = seedFromEnv();
            active = modelConfigRepository.findActiveDecrypted().orElse(null);
            if (active != null && active.apiKeyPlain() != null && !active.apiKeyPlain().isBlank()) {
                try {
                    chatModelProvider.init(buildFrom(active));
                    chatModelProvider.initStreaming(buildStreamingFrom(active));
                    log.info("LLM 初始化自环境变量引导记录(已写入 model_config): {} ({})",
                            active.name(), active.modelName());
                    return;
                } catch (Exception e) {
                    log.warn("环境变量引导记录建模型失败,回退到内存 env 模型: {}", e.getMessage());
                }
            }
        }
        // 3) 环境变量已配置(但无法/未导入 DB,如未配置主密钥):用 env/Nacos 配置建内存模型
        if (props.llmConfigured()) {
            chatModelProvider.init(buildFromProps());
            chatModelProvider.initStreaming(buildStreamingFromProps());
            log.info("LLM 初始化自环境变量/Nacos: {} ({})", props.baseUrl(), props.chatModel());
        } else {
            log.warn("LLM 未配置(sparrow.industry-chain.* 与 model_config 均无有效配置),AI 功能将不可用");
        }
    }

    /**
     * 把环境变量(sparrow.industry-chain.*)的 LLM 配置作为初始记录写入 model_config 并激活。
     * 仅在表为空时调用(由 initModel 保证)。API Key 以 AES-GCM 密文入库,与管理端手动新增一致。
     */
    private long seedFromEnv() {
        String encryptedKey = modelConfigRepository.encryptApiKey(props.apiKey());
        long id = modelConfigRepository.insertActive(
                "环境变量引导配置", props.baseUrl(), props.chatModel(), encryptedKey,
                3000, props.effectiveRequestTimeoutSeconds(), props.effectiveMaxRetries());
        log.info("已将环境变量 LLM 配置引导写入 model_config (id={}): {} ({})", id, props.baseUrl(), props.chatModel());
        return id;
    }

    public ChatModel buildFromProps() {
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.chatModel())
                .maxTokens(3000)
                .timeout(Duration.ofSeconds(props.effectiveRequestTimeoutSeconds()))
                .maxRetries(props.effectiveMaxRetries())
                .customParameters(providerCustomParameters(props.baseUrl(), props.chatModel()))
                .build();
    }

    public ChatModel buildFrom(ModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKeyPlain())
                .modelName(config.modelName())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxRetries(config.maxRetries())
                .customParameters(providerCustomParameters(config.baseUrl(), config.modelName()))
                .build();
    }

    /**
     * 流式模型(环境变量配置):供 Agent 总结/反思等文本生成路径逐 token 推送。
     * 流式模型不需要 maxRetries(失败由调用方回退到阻塞模型),timeout 与阻塞模型对齐。
     */
    public StreamingChatModel buildStreamingFromProps() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.chatModel())
                .maxTokens(3000)
                .timeout(Duration.ofSeconds(props.effectiveRequestTimeoutSeconds()))
                .customParameters(providerCustomParameters(props.baseUrl(), props.chatModel()))
                .build();
    }

    public StreamingChatModel buildStreamingFrom(ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKeyPlain())
                .modelName(config.modelName())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .customParameters(providerCustomParameters(config.baseUrl(), config.modelName()))
                .build();
    }

    /**
     * GLM-4.7 默认强制深度思考，产业链工作流的规划、检索和 Agent 轮次会因此长时间无首包。
     * 仅对智谱 GLM 的 OpenAI 兼容端点关闭 thinking，避免把供应商私有参数发送给其他模型服务。
     */
    public static Map<String, Object> providerCustomParameters(String baseUrl, String modelName) {
        String normalizedUrl = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        String normalizedModel = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        if (normalizedUrl.contains("bigmodel.cn") && normalizedModel.startsWith("glm-")) {
            return Map.of("thinking", Map.of("type", "disabled"));
        }
        return Map.of();
    }
}
