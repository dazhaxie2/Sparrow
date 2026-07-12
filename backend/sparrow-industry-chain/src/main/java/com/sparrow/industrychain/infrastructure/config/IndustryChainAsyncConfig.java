package com.sparrow.industrychain.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class IndustryChainAsyncConfig {

    @Bean("industryChainRunExecutor")
    public Executor industryChainRunExecutor() {
        return executor("industry-chain-run-", 4, 4, 40);
    }

    @Bean("industryChainAgentExecutor")
    public Executor industryChainAgentExecutor() {
        // 每个运行会并行提交 3 个角色 Agent。与父运行池隔离，避免父任务 join 子任务时线程饥饿。
        return executor("industry-chain-agent-", 12, 12, 60);
    }

    @Bean("industryChainForumExecutor")
    public Executor industryChainForumExecutor() {
        // 主持人总结是非关键路径；独立线程池防止慢模型占用调研编排线程。
        return executor("industry-chain-forum-", 2, 2, 20);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int corePoolSize, int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

