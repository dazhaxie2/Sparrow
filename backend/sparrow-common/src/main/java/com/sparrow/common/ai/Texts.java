package com.sparrow.common.ai;

/**
 * 通用文本处理工具。
 *
 * <p>compact / clean / hasText 三组实现在 sparrow-industry-chain 和 sparrow-user 共有
 * 7+ 处逐字复制。收敛到 sparrow-common/ai 包(全部拷贝均位于 AI/调研链路),通过静态方法
 * 统一调用。</p>
 */
public final class Texts {

    private Texts() { /* utility */ }

    /**
     * 折叠空白符为单空格并 trim,不截断。
     *
     * @return null-safe 折叠后的字符串;null 返回 ""。
     */
    public static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 折叠空白符为单空格,再按最大长度截断。
     *
     * @param value 原始文本,可为 null
     * @param max   最大长度(含)
     * @return 折叠并截断后的字符串;null 返回 ""。
     */
    public static String compact(String value, int max) {
        String collapsed = collapse(value);
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max);
    }

    /**
     * 非 null 且非空白。
     */
    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * null 或空白。
     */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
