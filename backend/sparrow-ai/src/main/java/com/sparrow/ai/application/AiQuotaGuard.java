package com.sparrow.ai.application;

import com.sparrow.ai.infrastructure.client.UserClient;
import com.sparrow.ai.infrastructure.config.AiProperties;
import com.sparrow.common.ai.AiHarness;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/** 一句话：配额门控——会员无限、免费用户日限额 Redis 计数,超限抛 429。 */
class AiQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(AiQuotaGuard.class);
    private static final String QUOTA_KEY_PREFIX = "sparrow:ai:quota:";

    private final AiProperties props;
    private final StringRedisTemplate redis;
    private final UserClient userClient;

    AiQuotaGuard(AiProperties props, StringRedisTemplate redis, UserClient userClient) {
        this.props = props;
        this.redis = redis;
        this.userClient = userClient;
    }

    /** 消耗一免费配额;会员返回 -1,免费用户返回剩余配额,超限抛 429。 */
    long consume(Long userId) {
        if (isMember(userId)) {
            return -1;
        }
        String key = QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now();
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1L) {
            redis.expire(key, Duration.ofDays(1));
        }
        long remaining = props.freeQuotaPerDay() - (used == null ? 0 : used);
        if (remaining < 0) {
            throw new BizException(429, "今日免费问答次数已用完,开通会员畅享无限次 AI 问答");
        }
        return remaining;
    }

    boolean isMember(Long userId) {
        try {
            ApiResponse<Map<String, Object>> resp = userClient.membership(userId);
            return resp != null && resp.data() != null
                    && Boolean.TRUE.equals(resp.data().get("member"));
        } catch (Exception e) {
            log.warn("会员校验失败,按非会员处理: userId={} err={}", userId, AiHarness.safeFailure(e));
            return false;
        }
    }
}
