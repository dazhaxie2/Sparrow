package com.sparrow.ai.infrastructure.client;

import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelPoolHeaders;
import com.sparrow.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;

/** 读取 sparrow-industry-chain 所拥有的模型池权威配置。 */
@FeignClient(name = "sparrow-industry-chain", contextId = "chainModelConfigClient",
        path = "/internal/chains/model-configs")
public interface ChainModelConfigClient {

    @GetMapping("/active")
    ApiResponse<ModelConfigRecord> active(
            @RequestHeader(ModelPoolHeaders.INTERNAL_TOKEN) String internalToken,
            @RequestParam("scene") String scene);
}
