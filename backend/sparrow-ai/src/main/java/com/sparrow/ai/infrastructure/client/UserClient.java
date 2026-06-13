package com.sparrow.ai.infrastructure.client;

import com.sparrow.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(name = "sparrow-user", contextId = "aiUserClient", path = "/internal/user")
public interface UserClient {

    @GetMapping("/{userId}/membership")
    ApiResponse<Map<String, Object>> membership(@PathVariable("userId") Long userId);
}
