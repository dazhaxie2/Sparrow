package com.sparrow.industrychain.application;

import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelConfigRules;
import com.sparrow.common.ai.model.ModelScene;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型池内部只读查询。
 *
 * <p>只向 {@code sparrow-ai} 暴露它拥有的两个场景，不允许通过内部契约读取产业链场景。
 * 明文 API Key 只存在于返回对象和消费服务内存中，不写日志、不进入管理端响应。</p>
 */
@Service
public class ModelPoolQueryService {

    private final ModelConfigRepository repository;

    public ModelPoolQueryService(ModelConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ModelConfigRecord activeForSparrowAi(String sceneValue) {
        ModelScene scene = ModelScene.fromDbValue(sceneValue);
        if (scene == null || !scene.ownedBySparrowAi()) {
            throw new BizException(400, "不支持的模型场景");
        }
        ModelConfig config = repository.findActiveDecrypted(scene)
                .orElseThrow(() -> new BizException(404, "该场景暂无激活模型配置"));
        if (config.scene() != scene || config.kind() != scene.expectedKind()) {
            throw new BizException(503, "激活模型配置场景或类型不匹配");
        }
        ModelConfigRules.validate(config.name(), config.baseUrl(), config.modelName(),
                config.scene(), config.kind(), config.maxTokens(), config.timeoutSeconds(), config.maxRetries());
        if (config.apiKeyPlain() == null || config.apiKeyPlain().isBlank()) {
            throw new BizException(503, "激活模型配置不可用");
        }
        return new ModelConfigRecord(config.id(), config.name(), config.baseUrl(), config.modelName(),
                config.apiKeyPlain(), config.maxTokens(), config.timeoutSeconds(), config.maxRetries(),
                true, config.scene(), config.kind());
    }
}
