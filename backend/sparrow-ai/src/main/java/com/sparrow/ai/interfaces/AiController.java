package com.sparrow.ai.interfaces;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.sparrow.ai.application.AiService;
import com.sparrow.ai.application.AiService.AskResult;
import com.sparrow.ai.infrastructure.config.SentinelRuleConfig;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.UserContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@Validated
public class AiController {

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/ask")
    @SentinelResource(value = SentinelRuleConfig.RESOURCE_AI_ASK, blockHandler = "askBlocked")
    public ApiResponse<AskResult> ask(@RequestBody @Validated AskRequest req) {
        return ApiResponse.ok(aiService.ask(UserContext.require(), req.question()));
    }

    public ApiResponse<AskResult> askBlocked(AskRequest req, BlockException ex) {
        throw new BizException(429, "AI 问答请求过于频繁,请稍后再试");
    }
}
