package com.sparrow.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserHeaderInterceptorTest {

    private UserHeaderInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new UserHeaderInterceptor();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void preHandleReadsUserIdHeaderIntoContext() {
        when(request.getHeader(TokenKeys.USER_ID_HEADER)).thenReturn("123");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertTrue(proceed);
        assertEquals(123L, UserContext.get());
    }

    @Test
    void preHandleReadsCorrectHeaderName() {
        // 锁定:必须读 X-User-Id 而非其他头名
        when(request.getHeader(TokenKeys.USER_ID_HEADER)).thenReturn("9");
        interceptor.preHandle(request, response, new Object());
        verify(request).getHeader("X-User-Id");
    }

    @Test
    void preHandleTreatsMissingHeaderAsAnonymous() {
        when(request.getHeader(TokenKeys.USER_ID_HEADER)).thenReturn(null);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserContext.get());
    }

    @Test
    void preHandleTreatsBlankHeaderAsAnonymous() {
        when(request.getHeader(TokenKeys.USER_ID_HEADER)).thenReturn("  ");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserContext.get());
    }

    @Test
    void preHandleSwallowsNonNumericHeaderAsAnonymous() {
        // gateway 已保证只注入合法数字头;但防御性:非法头不应 500,降级匿名
        when(request.getHeader(TokenKeys.USER_ID_HEADER)).thenReturn("abc");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserContext.get());
    }

    @Test
    void afterCompletionAlwaysClearsContext() {
        UserContext.set(99L);

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(UserContext.get());
    }
}
