package com.sparrow.ai.application;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagIndexer.fingerprint() 稳定性测试(反射调用 private static)。
 * 指纹用于"内容未变跳过 embedding"的卷复用判定,稳定性是核心契约:
 * 相同内容必须得到相同指纹,内容变化必须得到不同指纹。
 */
class RagIndexerFingerprintTest {

    @SuppressWarnings("unchecked")
    private static String fingerprint(List<String> nodeTexts, List<String> chunkTexts) throws Exception {
        Method m = RagIndexer.class.getDeclaredMethod("fingerprint", List.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, nodeTexts, chunkTexts);
    }

    @Test
    void stableForSameInput() throws Exception {
        List<String> nodes = List.of("火(石器时代,公元前).摘要", "蒸汽机(工业革命,18世纪).摘要");
        List<String> chunks = List.of("【火】内容块1", "【蒸汽机】内容块2");

        String fp1 = fingerprint(nodes, chunks);
        String fp2 = fingerprint(nodes, chunks);

        assertEquals(fp1, fp2, "相同输入必须产生相同指纹");
    }

    @Test
    void sensitiveToNodeTextChange() throws Exception {
        List<String> chunks = List.of("块");
        String fp1 = fingerprint(List.of("蒸汽机"), chunks);
        String fp2 = fingerprint(List.of("蒸汽机改"), chunks);
        assertNotEquals(fp1, fp2, "节点文本变化,指纹必须变化");
    }

    @Test
    void sensitiveToChunkTextChange() throws Exception {
        List<String> nodes = List.of("蒸汽机");
        String fp1 = fingerprint(nodes, List.of("块A"));
        String fp2 = fingerprint(nodes, List.of("块B"));
        assertNotEquals(fp1, fp2, "语料块变化,指纹必须变化");
    }

    @Test
    void emptyListsProduceStableHash() throws Exception {
        // 空输入不应抛异常,且稳定
        String fp1 = fingerprint(List.of(), List.of());
        String fp2 = fingerprint(List.of(), List.of());
        assertEquals(fp1, fp2);
    }

    @Test
    void fingerprintIsSha256HexString() throws Exception {
        String fp = fingerprint(List.of("x"), List.of());
        assertTrue(fp.matches("[0-9a-f]{64}"), "应为 64 位 hex (SHA-256): " + fp);
    }
}
