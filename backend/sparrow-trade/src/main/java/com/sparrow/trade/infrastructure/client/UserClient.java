package com.sparrow.trade.infrastructure.client;

import com.sparrow.common.api.ApiResponse;
import jakarta.validation.constraints.Min;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * trade 自持的 user 客户端,只用 grant 一个能力。
 * 与 graph/ai 各自的 UserClient 同名但有意独立(消费方契约自持)。
 */
@FeignClient(name = "sparrow-user", contextId = "tradeUserClient", path = "/internal/user")
public interface UserClient {

    record GrantRequest(@Min(1) int days) {
    }

    @PostMapping("/{userId}/membership/grant")
    ApiResponse<Map<String, Object>> grantMembership(@PathVariable("userId") Long userId,
                                                     @RequestBody GrantRequest req);
}
