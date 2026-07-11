package com.sparrow.common.ai;

import com.sparrow.common.exception.BizException;

/** Stable runtime bounds shared by every administrator-managed Agent profile. */
public final class AiAgentProfileRules {

    private AiAgentProfileRules() {
    }

    public static void validateRuntime(String systemPrompt, int contextMessages,
                                       int contextChars, int outputChars, int steps) {
        if (systemPrompt == null || systemPrompt.trim().length() < 20 || systemPrompt.length() > 20_000) {
            throw new BizException("系统提示词长度必须在 20 到 20000 字符之间");
        }
        if (contextMessages < 0 || contextMessages > 50
                || contextChars < 1000 || contextChars > 50_000
                || outputChars < 500 || outputChars > 100_000
                || steps < 1 || steps > 20) {
            throw new BizException("Agent 运行参数超出允许范围");
        }
    }
}
