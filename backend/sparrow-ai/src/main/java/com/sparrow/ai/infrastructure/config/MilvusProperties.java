package com.sparrow.ai.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 向量数据库配置属性。
 * 通过 sparrow.milvus 前缀配置 Milvus 连接参数。
 */
@ConfigurationProperties(prefix = "sparrow.milvus")
public record MilvusProperties(
        /**
         * Milvus 服务器地址
         */
        String host,
        /**
         * Milvus 服务器端口
         */
        int port,
        /**
         * 集合名称
         */
        String collection) {
}