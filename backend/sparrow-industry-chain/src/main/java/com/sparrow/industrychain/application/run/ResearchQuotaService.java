package com.sparrow.industrychain.application.run;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.infrastructure.client.UserClient;
import com.sparrow.industrychain.infrastructure.config.IndustryChainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;

@Service
public class ResearchQuotaService {

    private static final Logger log = LoggerFactory.getLogger(ResearchQuotaService.class);
    private static final String QUOTA_PREFIX = "sparrow:industry-chain:quota:";
    private static final DefaultRedisScript<Long> REFUND_SCRIPT = new DefaultRedisScript<>(
            "local used=tonumber(redis.call('get',KEYS[1]) or '0'); "
                    + "if used>0 then return redis.call('decr',KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final UserClient userClient;
    private final IndustryChainProperties props;

    public ResearchQuotaService(StringRedisTemplate redis, UserClient userClient, IndustryChainProperties props) {
        this.redis = redis;
        this.userClient = userClient;
        this.props = props;
    }

    public int consume(long userId) {
        int limit = isMember(userId) ? props.researchMemberPerDay() : props.researchFreePerDay();
        if (limit <= 0) throw new BizException(403, "当前账号没有深度调研额度");
        String key = QUOTA_PREFIX + userId + ":" + LocalDate.now();
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1) redis.expire(key, Duration.ofDays(2));
        if (used != null && used > limit) {
            redis.opsForValue().decrement(key);
            throw new BizException(429, "今日深度调研次数已用完");
        }
        return used == null ? -1 : Math.max(0, limit - used.intValue());
    }

    /** 异步任务未能提交时返还本次已扣额度，Lua 保证不会并发减成负数。 */
    public void refund(long userId) {
        String key = QUOTA_PREFIX + userId + ":" + LocalDate.now();
        redis.execute(REFUND_SCRIPT, List.of(key));
    }

    private boolean isMember(long userId) {
        try {
            ApiResponse<Map<String, Object>> response = userClient.membership(userId);
            return response != null && response.data() != null
                    && Boolean.TRUE.equals(response.data().get("member"));
        } catch (Exception error) {
            log.warn("调研会员校验失败，按免费用户处理: userId={}", userId, error);
            return false;
        }
    }
}
