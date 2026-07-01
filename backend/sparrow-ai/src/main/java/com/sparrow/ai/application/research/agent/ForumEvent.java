package com.sparrow.ai.application.research.agent;

/**
 * 论坛事件：Multi-Agent 协作过程中的一条发言/系统记录。
 *
 * <p>对照 BettaFish 的 forum.log 行记录。source 标识发言方：
 * INDUSTRY(行业 Agent)/QUERY(检索 Agent)/INSIGHT(洞察 Agent)/HOST(论坛主持人)/SYSTEM(系统)。
 * content 为发言正文；runId 用于区分同卡片的多次调研运行。
 */
public record ForumEvent(long cardId, long runId, String source, String content, String createdAt) {

    public static final String INDUSTRY = "INDUSTRY";
    public static final String QUERY = "QUERY";
    public static final String INSIGHT = "INSIGHT";
    public static final String HOST = "HOST";
    public static final String SYSTEM = "SYSTEM";

    /** 来源对应的中文显示名，供前端渲染气泡标签。 */
    public String sourceText() {
        return switch (source) {
            case INDUSTRY -> "行业 Agent";
            case QUERY -> "检索 Agent";
            case INSIGHT -> "洞察 Agent";
            case HOST -> "论坛主持人";
            case SYSTEM -> "系统";
            default -> source;
        };
    }
}
