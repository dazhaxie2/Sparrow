package com.sparrow.ai.infrastructure.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为 AI 问答接口注册兜底 QPS 限流规则。
 * 真实生产建议改用 Nacos/Apollo 等持久化数据源,这里只保证未配置远端规则时也有保护。
 */
@Component
public class SentinelRuleConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleConfig.class);

    public static final String RESOURCE_AI_ASK = "ai-ask";

    private final int askQps;
    private final boolean localFallback;

    public SentinelRuleConfig(@Value("${sparrow.sentinel.ai-ask-qps:5}") int askQps,
                              @Value("${sparrow.sentinel.local-fallback:true}") boolean localFallback) {
        this.askQps = askQps;
        this.localFallback = localFallback;
    }

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void registerDefaultRules() {
        if (!localFallback) {
            log.info("Sentinel 兜底限流已禁用,规则改由 Nacos 数据源/Dashboard 管理: resource={}", RESOURCE_AI_ASK);
            return;
        }
        FlowRule askRule = new FlowRule();
        askRule.setResource(RESOURCE_AI_ASK);
        askRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        askRule.setCount(askQps);
        FlowRuleManager.loadRules(List.of(askRule));
        log.info("Sentinel 兜底限流已注册: resource={} qps={}", RESOURCE_AI_ASK, askQps);
    }
}
