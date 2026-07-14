package com.sparrow.common.ai.model;

import com.sparrow.common.exception.BizException;

/**
 * 模型配置保存前的共享校验(与 {@code AiAgentProfileRules} 风格一致)。
 *
 * <p>两端服务在保存模型配置时调用,保证校验逻辑单点维护。
 * API Key 的非空校验由 owning 服务按"新增必填/更新可留空保留旧值"的语义自行处理,
 * 此处只校验与场景/类型/连接参数相关的稳定规则。</p>
 */
public final class ModelConfigRules {

    private ModelConfigRules() {
    }

    /**
     * 校验模型配置的核心字段。
     *
     * @param name        配置名称
     * @param baseUrl     接口地址
     * @param modelName   模型名
     * @param scene       场景(不能为 null)
     * @param kind        模型类型(不能为 null)
     * @param maxTokens   最大 token 数(chat 有效;embedding 时调用方可传 0)
     * @param timeoutSeconds 超时秒数
     * @param maxRetries  最大重试次数
     */
    public static void validate(String name, String baseUrl, String modelName,
                                ModelScene scene, ModelKind kind,
                                int maxTokens, int timeoutSeconds, int maxRetries) {
        if (name == null || name.isBlank()) {
            throw new BizException("配置名称不能为空");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException("base_url 不能为空");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new BizException("model_name 不能为空");
        }
        if (scene == null) {
            throw new BizException("模型场景不能为空");
        }
        if (kind == null) {
            throw new BizException("模型类型不能为空");
        }
        if (scene.expectedKind() != kind) {
            throw new BizException(scene.displayName() + " 场景仅支持 "
                    + scene.expectedKind().dbValue() + " 模型");
        }
        if (kind == ModelKind.CHAT) {
            if (maxTokens < 100 || maxTokens > 100_000) {
                throw new BizException("max_tokens 需在 100 到 100000 之间");
            }
        }
        if (timeoutSeconds < 5 || timeoutSeconds > 600) {
            throw new BizException("超时秒数需在 5 到 600 之间");
        }
        if (maxRetries < 0 || maxRetries > 10) {
            throw new BizException("最大重试次数需在 0 到 10 之间");
        }
    }
}
