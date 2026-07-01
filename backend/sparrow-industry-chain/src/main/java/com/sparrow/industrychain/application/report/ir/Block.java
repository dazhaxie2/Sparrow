package com.sparrow.industrychain.application.report.ir;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 富报告块：Document IR 中章节正文的最小渲染单元。
 *
 * <p>采用「type 字段 + 扁平字段」的多态表达（对照 BettaFish ReportEngine 的 Block oneOf 联合类型），
 * 便于 LLM 直接产出 JSON、Jackson 一次性反序列化、前端按 type 分发渲染。精选适合产业链报告的 11 种块类型：
 * heading / paragraph / list / table / swotTable / pestTable / blockquote / callout / kpiGrid / widget / hr。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Block(String type,
                    // heading
                    Integer level, String text, String anchor,
                    // paragraph / blockquote / callout / list-item
                    List<InlineRun> inlines, List<List<Block>> items,
                    // callout
                    String tone,
                    // table
                    TableRows rows,
                    // swot / pest
                    SwotTable swot, PestTable pest,
                    // kpi
                    KpiGrid kpi,
                    // widget (echarts 图表)
                    Widget widget) {

    // ===== 块类型常量 =====
    public static final String HEADING = "heading";
    public static final String PARAGRAPH = "paragraph";
    public static final String LIST = "list";
    public static final String TABLE = "table";
    public static final String SWOT = "swotTable";
    public static final String PEST = "pestTable";
    public static final String BLOCKQUOTE = "blockquote";
    public static final String CALLOUT = "callout";
    public static final String KPI = "kpiGrid";
    public static final String WIDGET = "widget";
    public static final String HR = "hr";

    // ===== 工厂方法：便于 ReportBuilder 构造，避免直接堆构造器参数 =====
    public static Block heading(int level, String anchor, String text) {
        return new Block(HEADING, level, text, anchor, null, null, null, null, null, null, null, null);
    }

    public static Block paragraph(List<InlineRun> inlines) {
        return new Block(PARAGRAPH, null, null, null, inlines, null, null, null, null, null, null, null);
    }

    public static Block list(List<List<Block>> items) {
        return new Block(LIST, null, null, null, null, items, null, null, null, null, null, null);
    }

    public static Block table(TableRows rows) {
        return new Block(TABLE, null, null, null, null, null, null, rows, null, null, null, null);
    }

    public static Block swot(SwotTable swot) {
        return new Block(SWOT, null, null, null, null, null, null, null, swot, null, null, null);
    }

    public static Block pest(PestTable pest) {
        return new Block(PEST, null, null, null, null, null, null, null, null, pest, null, null);
    }

    public static Block blockquote(List<InlineRun> inlines) {
        return new Block(BLOCKQUOTE, null, null, null, inlines, null, null, null, null, null, null, null);
    }

    public static Block callout(String tone, String title, List<Block> blocks) {
        // callout 的内部内容用 items 承载（List<List<Block>>，单层包裹）。
        return new Block(CALLOUT, null, title, null, null, List.of(blocks), tone, null, null, null, null, null);
    }

    public static Block kpi(KpiGrid kpi) {
        return new Block(KPI, null, null, null, null, null, null, null, null, null, kpi, null);
    }

    public static Block widget(Widget widget) {
        return new Block(WIDGET, null, null, null, null, null, null, null, null, null, null, widget);
    }

    public static Block hr() {
        return new Block(HR, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 表格数据：表头行 + 数据行，单元格内是 inline 片段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableRows(List<List<List<InlineRun>>> header, List<List<List<InlineRun>>> body, String caption) {
    }

    /** SWOT：优势/劣势/机会/威胁四象限，每项含要点、详情、影响标签。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SwotTable(List<SwotItem> strengths, List<SwotItem> weaknesses,
                            List<SwotItem> opportunities, List<SwotItem> threats) {
    }

    /** SWOT/PEST 单项。impact/trend 为枚举标签（如「高」「正面利好」），可空。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SwotItem(String title, String detail, String impact) {
    }

    /** PEST：政治/经济/社会/技术四维度，每项含要点、详情、趋势、来源编号。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PestTable(List<PestItem> political, List<PestItem> economic,
                            List<PestItem> social, List<PestItem> technological) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PestItem(String title, String detail, String trend, String source) {
    }

    /** KPI 卡片网格：标签、数值、单位、变化幅度。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KpiGrid(List<KpiItem> items, Integer cols) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KpiItem(String label, String value, String unit, String delta, String deltaTone) {
    }

    /** echarts 图表配置：widgetType 决定图表类型(bar/line/pie...)，data 为 echarts option 数据。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Widget(String widgetId, String widgetType, String title, Object data) {
    }
}
