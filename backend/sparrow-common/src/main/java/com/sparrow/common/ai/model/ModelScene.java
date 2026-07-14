package com.sparrow.common.ai.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 模型池场景枚举。每个场景对应一个独立的"模型槽",可单独激活一个模型配置。
 *
 * <p>场景来自代码中固定的消费点(非自由文本),保证两端服务(sparrow-ai / sparrow-industry-chain)
 * 共用同一套场景语义。新增消费场景时在此枚举追加,并同步更新 model_config.scene 列的取值。</p>
 *
 * <p>见 {@code docs/harness/model-pool-design.md} 的"消费方→场景映射"。</p>
 */
public enum ModelScene {

    /** 科技图 AI 对话:sparrow-ai 的 Agent + RAG 的 chat 模型。 */
    SPARROW_AI_CHAT("sparrow_ai_chat", "科技图 AI 对话", ModelKind.CHAT),

    /** 科技图向量检索:sparrow-ai 的 embedding 模型(独立配置,与 chat 不同 endpoint)。 */
    SPARROW_AI_EMBEDDING("sparrow_ai_embedding", "科技图向量检索", ModelKind.EMBEDDING),

    /** 产业链调研规划与问答:规划/证据核验/论坛总结。 */
    CHAIN_PLANNING("chain_planning", "调研规划与问答", ModelKind.CHAT),

    /** 产业链图谱抽取:结构化 JSON 生成。 */
    CHAIN_EXTRACTION("chain_extraction", "图谱抽取", ModelKind.CHAT),

    /** 产业链报告生成:文档 IR 与 Markdown。 */
    CHAIN_REPORT("chain_report", "报告生成", ModelKind.CHAT),

    /** 产业链 Agent 流式总结:逐 token 推送的流式模型。 */
    CHAIN_AGENT_STREAM("chain_agent_stream", "Agent 流式总结", ModelKind.CHAT);

    /** 数据库存储值(model_config.scene 列)。 */
    private final String dbValue;
    /** 前端展示用中文名。 */
    private final String displayName;
    /** 该固定消费场景唯一允许的模型类型。 */
    private final ModelKind expectedKind;

    ModelScene(String dbValue, String displayName, ModelKind expectedKind) {
        this.dbValue = dbValue;
        this.displayName = displayName;
        this.expectedKind = expectedKind;
    }

    @JsonValue
    public String dbValue() {
        return dbValue;
    }

    public String displayName() {
        return displayName;
    }

    public ModelKind expectedKind() {
        return expectedKind;
    }

    public boolean ownedBySparrowAi() {
        return this == SPARROW_AI_CHAT || this == SPARROW_AI_EMBEDDING;
    }

    /** 按数据库存储值解析枚举;未知值返回 null(容错,不抛异常)。 */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ModelScene fromDbValue(String value) {
        if (value == null || value.isBlank()) return null;
        for (ModelScene scene : values()) {
            if (scene.dbValue.equalsIgnoreCase(value.trim())) return scene;
        }
        return null;
    }
}
