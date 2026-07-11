package com.sparrow.common.security;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;

import java.util.Map;

/**
 * 管理员权限校验工具：供下游服务(经 Feign 查到用户 role 后)做统一的管理端鉴权。
 *
 * <p>Sparrow 无 Spring Security，角色判断由调用方查 sparrow-user 拿到 role 后传入本工具。
 */
public final class AdminGuard {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    private AdminGuard() {
    }

    /** 断言当前调用者是管理员，否则抛 403。 */
    public static void requireAdmin(String role) {
        if (!ROLE_ADMIN.equalsIgnoreCase(role)) {
            throw new BizException(403, "需要管理员权限");
        }
    }

    public static String roleOf(ApiResponse<? extends Map<String, ?>> response) {
        Object role = response == null || response.data() == null ? null : response.data().get("role");
        return role == null ? null : role.toString();
    }
}
