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
        // 2) 数据库无配置且环境变量已配置:用 env/Nacos 配置
        if (props.llmConfigured()) {
            chatModelProvider.init(buildFromProps());
            chatModelProvider.initStreaming(buildStreamingFromProps());
            log.info("LLM 初始化自环境变量/Nacos: {} ({})", props.baseUrl(), props.chatModel());
        } else {
            log.warn("LLM 未配置(sparrow.industry-chain.* 与 model_config 均无有效配置),AI 功能将不可用");
        }
    }

    public ChatModel buildFromProps() {
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.chatModel())
                .maxTokens(3000)
                .timeout(Duration.ofSeconds(props.effectiveRequestTimeoutSeconds()))
                .maxRetries(props.effectiveMaxRetries())
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
                .build();
    }

    public StreamingChatModel buildStreamingFrom(ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKeyPlain())
                .modelName(config.modelName())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }
}
