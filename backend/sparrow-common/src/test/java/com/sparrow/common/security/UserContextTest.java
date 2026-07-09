package com.sparrow.common.security;

import com.sparrow.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserContextTest {

    @AfterEach
    void clearThreadLocal() {
        // ThreadLocal 跨用例泄漏会导致用例间相互污染;每个用例后强制清。
        UserContext.clear();
    }

    @Test
    void getReturnsNullOnFreshContext() {
        assertNull(UserContext.get());
    }

    @Test
    void setAndGetRoundTrip() {
        UserContext.set(42L);
        assertEquals(42L, UserContext.get());
        assertEquals(42L, UserContext.require());
    }

    @Test
    void requireThrowsBizExceptionWith401WhenUnset() {
        BizException e = assertThrows(BizException.class, UserContext::require);
        assertEquals(401, e.getCode());
        assertEquals("请先登录", e.getMessage());
    }

    @Test
    void clearResetsContext() {
        UserContext.set(7L);
        UserContext.clear();
        assertNull(UserContext.get());
    }

    @Test
    void contextIsThreadLocalScoped() throws Exception {
        // ThreadLocal 语义验证:主线程 set 不应泄漏到其他线程
        UserContext.set(1L);
        AtomicReference<Long> otherThread = new AtomicReference<>();

        Thread t = new Thread(() -> otherThread.set(UserContext.get()));
        t.start();
        t.join();

        // 其他线程读不到主线程的值 → 证明真正 per-thread
        assertNull(otherThread.get());
        // 主线程自己的值仍在
        assertEquals(1L, UserContext.get());
    }
}
