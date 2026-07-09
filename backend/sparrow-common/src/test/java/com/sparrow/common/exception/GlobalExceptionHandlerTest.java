package com.sparrow.common.exception;

import com.sparrow.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.validation.FieldError;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBizMapsCodeAndMessage() {
        ApiResponse<Void> resp = handler.handleBiz(new BizException(403, "禁止访问"));
        assertEquals(403, resp.code());
        assertEquals("禁止访问", resp.message());
    }

    @Test
    void handleOtherReturns500() {
        ApiResponse<Void> resp = handler.handleOther(new RuntimeException("boom"));
        assertEquals(500, resp.code());
        assertEquals("系统繁忙,请稍后再试", resp.message());
    }

    @Test
    void handleValidationReturnsFirstFieldError() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(java.util.List.of(
                new FieldError("obj", "email", "格式不正确")));

        ApiResponse<Void> resp = handler.handleValidation(ex);

        assertEquals(400, resp.code());
        assertTrue(resp.message().contains("email"));
        assertTrue(resp.message().contains("格式不正确"));
    }

    @Test
    void handleValidationFallsBackWhenNoFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(java.util.List.of());

        ApiResponse<Void> resp = handler.handleValidation(ex);

        assertEquals(400, resp.code());
        assertEquals("参数校验失败", resp.message());
    }
}
