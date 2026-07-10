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
        // 3 个角色 Agent 需完全并行,corePoolSize=4 容纳 3 Agent + 余量;
        // maxPoolSize=8 容纳并发调研与 Agent 内部并行检索。
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(40);
        executor.setThreadNamePrefix("industry-chain-research-");
        executor.initialize();
        return executor;
    }
}


