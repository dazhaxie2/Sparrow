package com.sparrow.industrychain.interfaces;

import com.sparrow.common.ai.model.ModelConfigRecord;
import com.sparrow.common.ai.model.ModelPoolHeaders;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.application.ModelPoolQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 服务间模型池契约。
 *
 * <p>路径位于 {@code /internal/**}，不匹配网关公开路由。响应含模型运行所需的明文 API Key，
 * 因此不得把本端点迁入 {@code /api/chains/**}，也不得记录响应体。</p>
 */
@RestController
@RequestMapping("/internal/chains/model-configs")
public class InternalModelPoolController {

    private final ModelPoolQueryService service;
    private final String internalToken;

    public InternalModelPoolController(ModelPoolQueryService service,
                                       @Value("${sparrow.model-pool.internal-token:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    @GetMapping("/active")
    public ApiResponse<ModelConfigRecord> active(
            @RequestHeader(value = ModelPoolHeaders.INTERNAL_TOKEN, required = false) String token,
            @RequestParam("scene") String scene) {
        requireInternalToken(token);
        return ApiResponse.ok(service.activeForSparrowAi(scene));
    }

    private void requireInternalToken(String presented) {
        if (internalToken == null || internalToken.length() < 32) {
            throw new BizException(503, "内部模型池凭据未配置");
        }
        byte[] expected = internalToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BizException(403, "内部调用凭据无效");
        }
    }
}
