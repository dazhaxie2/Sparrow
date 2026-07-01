package com.sparrow.industrychain.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class IndustryChainAsyncConfig {

    @Bean("industryChainResearchExecutor")
    public Executor industryChainResearchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(40);
        executor.setThreadNamePrefix("industry-chain-research-");
        executor.initialize();
        return executor;
    }
}


