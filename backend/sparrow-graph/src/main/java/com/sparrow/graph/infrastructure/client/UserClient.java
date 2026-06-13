package com.sparrow.graph.infrastructure.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.sparrow.common.api.ApiResponse;

import java.util.Map;

/**
 * graph 自持的 user 服务客户端。
 * 仅需会员校验一个能力,DTO 副本与 trade/ai 各自的同名接口有意独立。
 */
@FeignClient(name = "sparrow-user", contextId = "graphUserClient", path = "/internal/user")
public interface UserClient {

    @GetMapping("/{userId}/membership")
    ApiResponse<Map<String, Object>> membership(@PathVariable("userId") Long userId);
}
