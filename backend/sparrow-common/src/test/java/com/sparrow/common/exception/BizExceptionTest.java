package com.sparrow.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BizExceptionTest {

    @Test
    void explicitCodeIsPreserved() {
        BizException e = new BizException(401, "请先登录");
        assertEquals(401, e.getCode());
        assertEquals("请先登录", e.getMessage());
    }

    @Test
    void messageOnlyDefaultsTo400() {
        BizException e = new BizException("参数错误");
        assertEquals(400, e.getCode());
        assertEquals("参数错误", e.getMessage());
    }

    @Test
    void isRuntimeExceptionSoGlobalHandlerCanCatchIt() {
        // 必须是 RuntimeException(@RestControllerAdvice 的 @ExceptionHandler 才能拦截)
        BizException e = new BizException(429, "限流");
        assertInstanceOf(RuntimeException.class, e);
    }

    @Test
    void canBeThrownAndCaught() {
        BizException thrown = assertThrows(BizException.class,
                () -> { throw new BizException(404, "不存在"); });
        assertEquals(404, thrown.getCode());
    }
}
