package com.sparrow.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenKeysTest {

    /**
     * 锁定 Redis token key 前缀格式。gateway/下游均依赖此前缀拼 token:
     *   TOKEN_KEY_PREFIX + token  →  "sparrow:token:abc123"
     * 尾部冒号被拼接逻辑依赖,绝不能漂移。
     */
    @Test
    void tokenKeyPrefixFormat() {
        assertEquals("sparrow:token:", TokenKeys.TOKEN_KEY_PREFIX);
        assertTrue(TokenKeys.TOKEN_KEY_PREFIX.startsWith("sparrow:"));
        assertTrue(TokenKeys.TOKEN_KEY_PREFIX.endsWith(":"),
                "前缀必须以冒号结尾,拼接 token 时才不会粘在一起");
    }

    @Test
    void userIdHeaderName() {
        assertEquals("X-User-Id", TokenKeys.USER_ID_HEADER);
    }

    @Test
    void tokenVersionPrefixFormat() {
        assertEquals("sparrow:token-version:", TokenKeys.TOKEN_VERSION_PREFIX);
        assertTrue(TokenKeys.TOKEN_VERSION_PREFIX.endsWith(":"));
    }

    @Test
    void concatenationProducesValidKey() {
        // 验证实际拼接结果符合 Redis key 规范(无空格、单层冒号分隔)
        String token = "abc-123-xyz";
        String key = TokenKeys.TOKEN_KEY_PREFIX + token;
        assertEquals("sparrow:token:abc-123-xyz", key);
        assertTrue(key.chars().noneMatch(c -> c == ' '));
    }
}
