package com.sparrow.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 下游服务从 gateway 注入的 X-User-Id 读用户身份。
 * gateway 已在边界无条件剥除入站伪造头并依据 Redis token 重新注入,
 * 因此下游可信任 header,不再查 Redis(替代原 AuthInterceptor)。
 */
@Component
public class UserHeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userIdHeader = request.getHeader(TokenKeys.USER_ID_HEADER);
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            try {
                UserContext.set(Long.valueOf(userIdHeader));
            } catch (NumberFormatException ignored) {
                // 非法 header 视同匿名
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
