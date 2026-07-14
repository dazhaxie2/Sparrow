package com.sparrow.common.ai.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 模型类型:决定消费方构建 {@code OpenAiChatModel} 还是 {@code OpenAiEmbeddingModel}。
 *
 * <p>embedding 模型与 chat 模型是不同 endpoint、不同参数(无 maxTokens,有 dimensions),
 * 不能互相复用,因此用此枚举区分。</p>
 */
public enum ModelKind {

    /** 对话模型(chat / streaming chat)。 */
    CHAT("chat"),

    /** 向量模型(embedding)。 */
    EMBEDDING("embedding");

    private final String dbValue;

    ModelKind(String dbValue) {
        this.dbValue = dbValue;
    }

    @JsonValue
    public String dbValue() {
        return dbValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ModelKind fromDbValue(String value) {
        if (value == null || value.isBlank()) return null;
        for (ModelKind kind : values()) {
            if (kind.dbValue.equalsIgnoreCase(value.trim())) return kind;
        }
        return null;
    }
}
