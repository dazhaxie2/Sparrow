package com.sparrow.industrychain.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class IndustryChainAsyncConfigTest {

    @Test
    void fourConcurrentRunsCanCompleteTheirThreeAgentTasks() {
        IndustryChainAsyncConfig config = new IndustryChainAsyncConfig();
        ThreadPoolTaskExecutor runs = (ThreadPoolTaskExecutor) config.industryChainRunExecutor();
        ThreadPoolTaskExecutor agents = (ThreadPoolTaskExecutor) config.industryChainAgentExecutor();
        CountDownLatch allRunsStarted = new CountDownLatch(4);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                CompletableFuture<?>[] completedRuns = IntStream.range(0, 4)
                        .mapToObj(index -> CompletableFuture.runAsync(() -> {
                            allRunsStarted.countDown();
                            try {
                                allRunsStarted.await(1, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            }
                            CompletableFuture<?>[] children = IntStream.range(0, 3)
                                    .mapToObj(child -> CompletableFuture.runAsync(() -> {}, agents))
                                    .toArray(CompletableFuture[]::new);
                            CompletableFuture.allOf(children).join();
                        }, runs))
                        .toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(completedRuns).join();
            });
        } finally {
            runs.shutdown();
            agents.shutdown();
        }
    }
}
