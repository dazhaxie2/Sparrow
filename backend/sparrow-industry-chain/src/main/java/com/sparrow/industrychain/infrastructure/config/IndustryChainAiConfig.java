package com.sparrow.industrychain.infrastructure.config;

import com.sparrow.common.ai.model.ModelKind;
import com.sparrow.common.ai.model.ModelScene;
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

    /** 本服务消费的场景(模型池中 sparrow-ai 的场景不在此装配)。 */
    private static final ModelScene[] OWNED_SCENES = {
            ModelScene.CHAIN_PLANNING, ModelScene.CHAIN_EXTRACTION,
            ModelScene.CHAIN_REPORT, ModelScene.CHAIN_AGENT_STREAM
    };

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initModel() {
        // 按场景装配:每个场景优先取 DB 激活配置 → 否则 env 兜底 → 都没有则该场景无模型。
        // 首次启动且表空时,把 env 配置导入为各场景的 active 记录(bootstrap)。
        boolean needBootstrap = props.llmConfigured() && modelConfigRepository.count() == 0
                && modelConfigRepository.encryptionReady();
        if (needBootstrap) {
            seedScenesFromEnv();
        }
        int assembled = 0;
        for (ModelScene scene : OWNED_SCENES) {
            ModelConfig active = modelConfigRepository.findActiveDecrypted(scene).orElse(null);
            if (active != null && active.apiKeyPlain() != null && !active.apiKeyPlain().isBlank()) {
                try {
                    if (active.scene() != scene) {
                        throw new IllegalArgumentException("数据库返回的模型场景不匹配");
                    }
                    requireChatConfig(active);
                    chatModelProvider.init(scene, buildFrom(active));
                    chatModelProvider.initStreaming(scene, buildStreamingFrom(active));
                    assembled++;
                } catch (Exception e) {
                    log.warn("[{}] 数据库激活配置建模型失败,回退到环境变量: {}", scene.dbValue(), e.getMessage());
                    if (props.llmConfigured()) {
                        chatModelProvider.init(scene, buildFromProps());
                        chatModelProvider.initStreaming(scene, buildStreamingFromProps());
                        assembled++;
                    }
                }
            } else if (props.llmConfigured()) {
                // 该场景无 DB 配置但 env 可用:用 env 建内存模型(可能因未配主密钥未导入 DB)
                chatModelProvider.init(scene, buildFromProps());
                chatModelProvider.initStreaming(scene, buildStreamingFromProps());
                assembled++;
            }
        }
        if (assembled == 0) {
            log.warn("LLM 未配置(sparrow.industry-chain.* 与 model_config 均无有效配置),AI 功能将不可用");
        } else {
            log.info("LLM 场景化装配完成: {}/{} 个场景可用", assembled, OWNED_SCENES.length);
        }
    }

    /**
     * 把环境变量(sparrow.industry-chain.*)的 LLM 配置导入为本服务各场景的 active 记录。
     * 仅在表为空时调用(由 initModel 保证)。各场景初始共用同一套 env 配置,
     * 管理员之后可在管理端按场景分别切换不同模型。
     */
    private void seedScenesFromEnv() {
        String encryptedKey = modelConfigRepository.encryptApiKey(props.apiKey());
        for (ModelScene scene : OWNED_SCENES) {
            modelConfigRepository.insertActive(
                    "环境变量引导·" + scene.displayName(), props.baseUrl(), props.chatModel(), encryptedKey,
                    3000, props.effectiveRequestTimeoutSeconds(), props.effectiveMaxRetries(),
                    scene, ModelKind.CHAT);
        }
        log.info("已将环境变量 LLM 配置引导写入 {} 个场景的 model_config", OWNED_SCENES.length);
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
        requireChatConfig(config);
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
        requireChatConfig(config);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKeyPlain())
                .modelName(config.modelName())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .customParameters(providerCustomParameters(config.baseUrl(), config.modelName()))
                .build();
    }

    private static void requireChatConfig(ModelConfig config) {
        if (config == null || config.kind() != ModelKind.CHAT
                || config.scene() == null || config.scene().expectedKind() != ModelKind.CHAT) {
            throw new IllegalArgumentException("产业链场景只能构建 chat 模型");
        }
    }

    /**
     * GLM-4.7 与 DeepSeek V4 默认可能启用深度思考，产业链工作流的规划、检索和 Agent 轮次
     * 会因此长时间无首包。仅对已确认支持该参数的供应商与模型关闭 thinking，避免把供应商
     * 私有参数发送给其他模型服务。
     */
    public static Map<String, Object> providerCustomParameters(String baseUrl, String modelName) {
        String normalizedUrl = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        String normalizedModel = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        boolean bigModelGlm = normalizedUrl.contains("bigmodel.cn") && normalizedModel.startsWith("glm-");
        boolean deepSeekV4 = normalizedUrl.contains("api.deepseek.com")
                && normalizedModel.startsWith("deepseek-v4-");
        if (bigModelGlm || deepSeekV4) {
            return Map.of("thinking", Map.of("type", "disabled"));
        }
        return Map.of();
    }
}
