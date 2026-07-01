package com.sparrow.ai.application.research.ir;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 富报告章节：标题、锚点、排序号与一组块。
 *
 * <p>对照 BettaFish ReportEngine 的 ChapterIR。anchor 全局唯一，供目录跳转使用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChapterIr(String chapterId, String title, String anchor, int order,
                        String summary, List<Block> blocks) {
}
