package com.sparrow.common.ai.model;

/** 跨服务模型池 HTTP 契约中的固定请求头。 */
public final class ModelPoolHeaders {

    public static final String INTERNAL_TOKEN = "X-Sparrow-Model-Pool-Token";

    private ModelPoolHeaders() {
    }
}
