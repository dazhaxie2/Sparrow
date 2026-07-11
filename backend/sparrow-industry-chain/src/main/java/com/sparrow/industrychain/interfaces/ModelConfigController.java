package com.sparrow.industrychain.interfaces;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.security.UserContext;
import com.sparrow.industrychain.application.ModelConfigService;
import com.sparrow.industrychain.application.ModelConfigService.SaveConfig;
import com.sparrow.industrychain.application.ModelConfigService.TestConfig;
import com.sparrow.industrychain.application.ModelConfigService.TestResult;
import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository.AuditRow;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型配置管理接口(管理员)。
 *
 * <p>基路径 {@code /api/chains/admin/model-configs},复用网关既有 {@code /api/chains/**} 路由,
 * 无需改网关。每个端点先取 {@link UserContext#require()} 得到操作者 id,再由 Service 经 Feign 校验角色。
 */
@RestController
@RequestMapping("/api/chains/admin/model-configs")
@Validated
public class ModelConfigController {

    private final ModelConfigService service;

    public ModelConfigController(ModelConfigService service) {
        this.service = service;
    }

    /** 列出全部配置(api_key 脱敏)。 */
    @GetMapping
    public ApiResponse<List<ModelConfig>> list() {
        long operatorId = UserContext.require();
        service.requireAdmin(operatorId);
        return ApiResponse.ok(service.list());
    }

    /** 测试连接(不落库)。apiKey 为空时复用当前激活配置的 key。 */
    @PostMapping("/test")
    public ApiResponse<TestResult> test(@RequestBody TestConfig req) {
        long operatorId = UserContext.require();
        TestResult result = service.test(req, operatorId);
        service.recordTest(operatorId, req, result);
        return ApiResponse.ok(result);
    }

    /** 保存(新增或更新)。id 为空=新增;apiKey 为空=保留旧 key。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody SaveConfig req) {
        long operatorId = UserContext.require();
        long id = service.save(req, operatorId);
        return ApiResponse.ok(Map.of("id", id));
    }

    /** 激活指定配置(原子切换,立即对后续请求生效)。 */
    @PostMapping("/{configId}/activate")
    public ApiResponse<Map<String, Object>> activate(@PathVariable long configId) {
        long operatorId = UserContext.require();
        service.activate(configId, operatorId);
        return ApiResponse.ok(Map.of("ok", true));
    }

    /** 审计记录。 */
    @GetMapping("/audits")
    public ApiResponse<List<AuditRow>> audits(@RequestParam(defaultValue = "50")
                                              @Min(1) @Max(200) int limit) {
        long operatorId = UserContext.require();
        service.requireAdmin(operatorId);
        return ApiResponse.ok(service.audits(limit));
    }
}
