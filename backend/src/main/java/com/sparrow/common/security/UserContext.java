package com.sparrow.common.security;

import com.sparrow.common.exception.BizException;

public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT.set(userId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static Long require() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new BizException(401, "请先登录");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
