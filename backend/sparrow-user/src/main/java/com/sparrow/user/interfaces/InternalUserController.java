package com.sparrow.user.interfaces;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.user.application.UserService;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仅供 compose 网络内其他服务通过 Feign 调用。
 * gateway 路由表不暴露 /internal/**,外部不可达。
 */
@RestController
@RequestMapping("/internal/user")
@Validated
public class InternalUserController {

    public record GrantRequest(@Min(1) int days) {
    }

    private final UserService userService;

    public InternalUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{userId}/membership")
    public ApiResponse<Map<String, Object>> membership(@PathVariable Long userId) {
        return ApiResponse.ok(Map.of("member", userService.isMember(userId)));
    }

    @PostMapping("/{userId}/membership/grant")
    public ApiResponse<Map<String, Object>> grant(@PathVariable Long userId,
                                                  @RequestBody @Validated GrantRequest req) {
        userService.grantMembership(userId, req.days());
        return ApiResponse.ok(Map.of("ok", true));
    }
}
