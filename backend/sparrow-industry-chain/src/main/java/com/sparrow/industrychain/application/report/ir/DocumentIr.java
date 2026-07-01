package com.sparrow.industrychain.application.report.ir;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 富报告文档中间表示(IR)：报告的顶层结构。
 *
 * <p>对照 BettaFish ReportEngine 的 Document IR。一份调研报告 = metadata + 若干章节。
 * 前端按章节与块递归渲染交互式报告(目录、SWOT/PEST、图表、来源徽章)。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentIr(Metadata metadata, List<ChapterIr> chapters) {

    /** 报告元信息：标题、副标题、生成时间、查询主题、目录条目。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metadata(String title, String subtitle, String generatedAt, String query,
                           List<TocEntry> toc) {
    }

    /** 目录条目：层级、显示文本、锚点。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TocEntry(int level, String display, String anchor) {
    }
}
