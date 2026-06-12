package com.sparrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sparrow.milvus")
public record MilvusProperties(String host, int port, String collection) {
}
