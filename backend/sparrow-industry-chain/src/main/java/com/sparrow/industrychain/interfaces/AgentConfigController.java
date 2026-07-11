package com.sparrow.industrychain.interfaces;

import com.sparrow.common.ai.AiAgentProfile;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.security.UserContext;
import com.sparrow.industrychain.application.config.IndustryAgentConfigService;
import com.sparrow.industrychain.application.config.IndustryAgentConfigService.SaveRequest;
import com.sparrow.industrychain.infrastructure.persistence.AgentConfigRepository.AuditRow;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chains/admin/agent-configs")
@Validated
public class AgentConfigController {

    private final IndustryAgentConfigService service;

    public AgentConfigController(IndustryAgentConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AiAgentProfile>> list() {
        long operatorId = UserContext.require();
        return ApiResponse.ok(service.list(operatorId));
    }

    @PostMapping
    public ApiResponse<AiAgentProfile> save(@RequestBody SaveRequest request) {
        long operatorId = UserContext.require();
        return ApiResponse.ok(service.save(operatorId, request));
    }

    @GetMapping("/audits")
    public ApiResponse<List<AuditRow>> audits(@RequestParam(defaultValue = "50")
                                              @Min(1) @Max(200) int limit) {
        long operatorId = UserContext.require();
        return ApiResponse.ok(service.audits(operatorId, limit));
    }
}
