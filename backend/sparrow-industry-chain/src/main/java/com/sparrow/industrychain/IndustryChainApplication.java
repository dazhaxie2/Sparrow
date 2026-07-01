package com.sparrow.industrychain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 产业链调研服务启动类。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.sparrow.industrychain.infrastructure.client")
@EnableAsync
@ComponentScan({"com.sparrow.industrychain", "com.sparrow.common"})
public class IndustryChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndustryChainApplication.class, args);
    }
}
