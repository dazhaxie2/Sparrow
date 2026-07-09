package com.sparrow.ai.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagIndexer 纯函数测试:chunk() 分块逻辑。
 * chunk 是 package-private static,本测试与 RagIndexer 同包(com.sparrow.ai.application)可直接访问。
 */
class RagIndexerChunkTest {

    @Test
    void shortTextReturnsSingleChunk() {
        List<String> parts = RagIndexer.chunk("一段短文本", 500);
        assertEquals(1, parts.size());
        assertEquals("一段短文本", parts.get(0));
    }

    @Test
    void emptyOrBlankReturnsEmpty() {
        assertTrue(RagIndexer.chunk("", 500).isEmpty());
        assertTrue(RagIndexer.chunk("   \n  ", 500).isEmpty());
    }

    @Test
    void longTextSplitsBySizeWhenNoSentenceEnd() {
        // 无句末符号 → 纯按 size 切
        String text = "a".repeat(1200);
        List<String> parts = RagIndexer.chunk(text, 500);
        assertTrue(parts.size() >= 2, "应至少切成 2 块");
        // 拼接后长度 == 原文(strip 后)
        assertEquals(1200, String.join("", parts).length());
    }

    @Test
    void prefersSentenceBoundaryOverHardCut() {
        // size 内有句号 → 在句号后断开,保留句子完整
        String s1 = "第一句话。";  // 5 字符
        String s2 = "第二句话。";  // 5 字符
        String s3 = "第三句很长".repeat(200);  // 远超 size
        String text = s1 + s2 + s3;
        List<String> parts = RagIndexer.chunk(text, 10);
        // 第一块应在第一个句号处断(包含"第一句话。")
        assertTrue(parts.size() >= 2);
        assertTrue(parts.get(0).contains("第一句话"), "第一块应含首句");
    }

    @Test
    void chunksAreStrippedAndNonEmpty() {
        // 每块都 strip 过,不返回空块
        String text = "句一。\n\n\n句二。";
        List<String> parts = RagIndexer.chunk(text, 5);
        for (String p : parts) {
            assertTrue(!p.isEmpty(), "不应有空块");
            // 不以换行开头(已 strip)
            assertTrue(p.charAt(0) != '\n');
        }
    }
}
