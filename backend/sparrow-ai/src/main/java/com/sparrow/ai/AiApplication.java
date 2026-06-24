package com.sparrow.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Sparrow AI 服务启动类。
 * 提供基于科技树图谱的 AI 问答能力,支持 Agent 工具链、RAG 检索增强和规则引擎三种模式。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.sparrow.ai.infrastructure.client")
@EnableKafka
@EnableAsync
@ComponentScan({"com.sparrow.ai", "com.sparrow.common"})
public class AiApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
