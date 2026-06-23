package com.sparrow.ai.interfaces;

import com.sparrow.ai.application.AiService;
import com.sparrow.ai.application.AiService.ApplicationClassifyRequest;
import com.sparrow.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仅供 compose 网络内其他服务通过 Feign 调用的 AI 接口。
 * 与 sparrow-graph 的 {@code AiClient} 对称,复用 {@link AiService} 的 LLM 能力。
 */
@RestController
@RequestMapping("/internal/ai")
public class InternalAiController {

    private final AiService aiService;

    public InternalAiController(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * 判定材料节点的哪些邻居是下游应用/产业链。
     * LLM 未配置/异常时返回空列表(调用方降级为不显示应用区块)。
     */
    @PostMapping("/applications")
    public ApiResponse<List<Long>> classifyApplications(@RequestBody ApplicationClassifyRequest req) {
        return ApiResponse.ok(aiService.classifyApplications(req));
    }
}
