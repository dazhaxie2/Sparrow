package com.sparrow.industrychain.infrastructure.client;

import com.sparrow.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 用户服务 Feign 客户端。
 * 调用 sparrow-user 服务获取用户会员信息。
 */
@FeignClient(name = "sparrow-user", contextId = "industryChainUserClient", path = "/internal/user")
public interface UserClient {

    /**
     * 获取用户会员状态。
     *
     * @param userId 用户 ID
     * @return 包含会员状态的响应,data 中包含 member 字段
     */
    @GetMapping("/{userId}/membership")
    ApiResponse<Map<String, Object>> membership(@PathVariable("userId") Long userId);
}

