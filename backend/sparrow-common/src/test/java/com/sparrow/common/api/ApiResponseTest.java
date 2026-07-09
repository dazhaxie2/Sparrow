package com.sparrow.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiResponseTest {

    @Test
    void okWrapsDataWithSuccessEnvelope() {
        ApiResponse<String> resp = ApiResponse.ok("hello");
        assertEquals(0, resp.code());
        assertEquals("ok", resp.message());
        assertEquals("hello", resp.data());
    }

    @Test
    void okAllowsNullData() {
        ApiResponse<Object> resp = ApiResponse.ok(null);
        assertEquals(0, resp.code());
        assertNull(resp.data());
    }

    @Test
    void errorCarriesCodeAndMessageWithoutData() {
        ApiResponse<Void> resp = ApiResponse.error(403, "forbidden");
        assertEquals(403, resp.code());
        assertEquals("forbidden", resp.message());
        assertNull(resp.data());
    }

    @Test
    void errorPreservesBusinessErrorCodes() {
        // 锁定常见业务码契约:401 未登录 / 404 不存在 / 429 限流 / 500 系统
        assertNotNull(ApiResponse.error(401, "请先登录"));
        assertNotNull(ApiResponse.error(404, "不存在"));
        assertNotNull(ApiResponse.error(429, "请求过于频繁"));
        assertEquals(500, ApiResponse.error(500, "系统繁忙").code());
    }
}
