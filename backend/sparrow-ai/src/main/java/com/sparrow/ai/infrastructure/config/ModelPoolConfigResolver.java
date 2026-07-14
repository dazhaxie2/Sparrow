package com.sparrow.ai.infrastructure.config;

import com.sparrow.ai.infrastructure.client.ChainModelConfigClient;
import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelConfigRules;
import com.sparrow.common.ai.model.ModelKind;
import com.sparrow.common.ai.model.ModelScene;
import com.sparrow.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * sparrow-ai 启动期模型配置解析器。
 *
 * <p>优先读取权威模型池；内部服务不可用、响应无效或场景未配置时，才使用现有
 * {@code sparrow.ai.*} 环境配置。日志只记录场景、来源和配置 id，不记录响应对象或 API Key。</p>
 */
@Component
public class ModelPoolConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelPoolConfigResolver.class);
    private static final int FALLBACK_TIMEOUT_SECONDS = 60;

    private final ChainModelConfigClient client;
    private final AiProperties props;
    private final String internalToken;

    public ModelPoolConfigResolver(ChainModelConfigClient client, AiProperties props,
                                   @Value("${sparrow.model-pool.internal-token:}") String internalToken) {
        this.client = client;
        this.props = props;
        this.internalToken = internalToken;
    }

    public ModelConfigRecord resolve(ModelScene scene) {
        if (scene == null || !scene.ownedBySparrowAi()) {
            throw new IllegalArgumentException("sparrow-ai 不消费该模型场景");
        }
        try {
            if (internalToken == null || internalToken.length() < 32) {
                throw new IllegalStateException("内部模型池凭据未配置");
            }
            ApiResponse<ModelConfigRecord> response = client.active(internalToken, scene.dbValue());
            ModelConfigRecord remote = response == null ? null : response.data();
            if (response == null || response.code() != 0 || remote == null) {
                throw new IllegalStateException("模型池未返回有效配置");
            }
            validate(remote, scene);
            log.info("模型池配置已装配: scene={} source=remote configId={}", scene.dbValue(), remote.id());
            return remote;
        } catch (Exception error) {
            // 不输出异常 message，避免第三方客户端把响应体或请求细节拼入日志。
            log.warn("模型池配置读取失败: scene={} errorType={}，尝试环境配置兜底",
                    scene.dbValue(), error.getClass().getSimpleName());
            ModelConfigRecord fallback = fallback(scene);
            if (fallback != null) {
                log.info("模型池配置已装配: scene={} source=environment", scene.dbValue());
            } else {
                log.warn("模型场景不可用: scene={}，模型池与环境配置均无有效值", scene.dbValue());
            }
            return fallback;
        }
    }

    private static void validate(ModelConfigRecord config, ModelScene expectedScene) {
        if (!config.active() || config.scene() != expectedScene || config.kind() != expectedScene.expectedKind()) {
            throw new IllegalStateException("模型池场景或类型不匹配");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("模型池配置缺少 API Key");
        }
        ModelConfigRules.validate(config.name(), config.baseUrl(), config.modelName(),
                config.scene(), config.kind(), config.maxTokens(),
                config.timeoutSeconds(), config.maxRetries());
    }

    private ModelConfigRecord fallback(ModelScene scene) {
        if (!props.llmConfigured()) {
            return null;
        }
        String modelName = scene == ModelScene.SPARROW_AI_EMBEDDING
                ? props.embeddingModel()
                : props.chatModel();
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        ModelKind kind = scene.expectedKind();
        int maxTokens = kind == ModelKind.CHAT ? 3000 : 0;
        ModelConfigRecord fallback = new ModelConfigRecord(null, "环境配置兜底", props.baseUrl(),
                modelName, props.apiKey(), maxTokens, FALLBACK_TIMEOUT_SECONDS, 0,
                true, scene, kind);
        try {
            validate(fallback, scene);
            return fallback;
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
