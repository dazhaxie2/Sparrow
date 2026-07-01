package com.sparrow.ai.application.research.ir;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 富报告内联片段：一段文本 + 作用于其上的标记(mark)。
 *
 * <p>对照 BettaFish ReportEngine 的 InlineRun：段落正文只存在于 inlines，不再存裸 markdown 字符串。
 * source mark 是本项目新增，对应报告里的 {@code [S1]} 来源引用，前端渲染为可点击徽章。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InlineRun(String text, List<Mark> marks) {

    /** 内联标记：加粗/斜体/链接/来源引用等。value 用于来源编号或颜色值。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mark(String type, String value, String href, String title) {

        public static Mark bold() { return new Mark("bold", null, null, null); }

        public static Mark italic() { return new Mark("italic", null, null, null); }

        public static Mark code() { return new Mark("code", null, null, null); }

        public static Mark source(String ref) { return new Mark("source", ref, null, null); }

        public static Mark link(String href, String title) { return new Mark("link", null, href, title); }

        public static Mark color(String value) { return new Mark("color", value, null, null); }

        public static Mark highlight() { return new Mark("highlight", null, null, null); }
    }
}
