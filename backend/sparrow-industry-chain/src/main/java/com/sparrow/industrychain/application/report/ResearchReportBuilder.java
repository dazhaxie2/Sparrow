package com.sparrow.industrychain.application.report;

import com.sparrow.industrychain.application.config.IndustryAgentConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.industrychain.application.report.ir.Block;
import com.sparrow.industrychain.application.report.ir.Block.SwotItem;
import com.sparrow.industrychain.application.report.ir.Block.SwotTable;
import com.sparrow.industrychain.application.report.ir.DocumentIr;
import com.sparrow.industrychain.application.report.ir.DocumentIr.Metadata;
import com.sparrow.industrychain.application.report.ir.DocumentIr.TocEntry;
import com.sparrow.industrychain.application.report.ir.ChapterIr;
import com.sparrow.industrychain.application.report.ir.InlineRun;
import com.sparrow.industrychain.application.report.ir.InlineRun.Mark;
import com.sparrow.industrychain.application.report.ir.IrValidator;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.llm.WebSearchClient.SearchSource;
import com.sparrow.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告 Agent：把多 Agent 结论 + 论坛记录整合为 Document IR(结构化富报告)。
 *
 * <p>对照 BettaFish ReportEngine 的 ReportAgent。流程：
 * <ol>
 *   <li>让 LLM 基于核验证据产出 IR JSON(章节/blocks/inline/source 引用)；</li>
 *   <li>{@link IrValidator} 校验来源引用合法性，失败触发一次修复重试；</li>
 *   <li>顺带产出降级 Markdown(用于 IR 渲染失败兜底与来源附录)。</li>
 * </ol>
 */
@Component
public class ResearchReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(ResearchReportBuilder.class);
    private static final Pattern SOURCE_REFERENCE = Pattern.compile("\\[(S\\d+)]");

    private final ChatModelProvider chat;
    private final ObjectMapper objectMapper;
    private final IrValidator validator;
    private IndustryAgentConfigService agentConfigs;

    public ResearchReportBuilder(ChatModelProvider chat, ObjectMapper objectMapper, IrValidator validator) {
        this.chat = chat;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Autowired(required = false)
    void setAgentConfigs(IndustryAgentConfigService agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    /** 构建报告。返回 IR JSON 字符串与降级 Markdown。 */
    public ReportResult build(String title, String evidence, JsonNode graphJson,
                              List<SearchSource> sources, String forumDigest) {
        if (!chat.available()) throw new BizException(503, "AI 服务未配置，无法生成报告");
        Set<String> validRefs = new HashSet<>(sources.stream().map(SearchSource::sourceRef).toList());

        String irJson = chat.chat(reportPrompt() + "\n\n"
                + buildIrPrompt(title, evidence, graphJson, sources, forumDigest));
        DocumentIr ir = parseIr(irJson, validRefs);
        if (ir == null) {
            String repaired = chat.chat("修复下面内容为符合 schema 的 Document IR JSON，"
                    + "删除来源编号不在 " + validRefs + " 内的 source 标记，不要新增事实，只输出 JSON：\n"
                    + compact(irJson, 8000));
            ir = parseIr(repaired, validRefs);
        }
        if (ir == null) {
            log.warn("报告 Agent 连续两次产出的 IR 无法解析，使用已核验证据生成安全降级 IR");
            ir = fallbackIr(title, evidence, validRefs);
        }

        List<String> errors = validator.validate(ir, validRefs);
        if (!errors.isEmpty()) {
            log.warn("报告 IR 校验存在问题(将尝试继续渲染): {}", errors);
        }
        ir = enrichToc(ir);
        String irString = writeJson(ir);

        // 降级 Markdown：让 LLM 基于同一证据产出，并附来源附录
        String markdownFallback = "# " + title + "\n\n## 已核验证据\n\n"
                + (evidence == null || evidence.isBlank() ? "暂无可展示的已核验证据。" : evidence.trim());
        String markdown = chat.chatOr(reportPrompt() + "\n\n" + """
                你是产业链报告 Agent。基于已核验事实和关系图，输出中文 Markdown 深度报告，
                必须包含：摘要、范围与方法、上游、中游、下游、核心企业与竞争、风险、机会与趋势、待核验事项。
                所有关键结论都要使用 [S1] 形式引用来源；不得引入材料之外的事实。

                核验证据：%s
                关系图 JSON：%s
                """.formatted(compact(evidence, 8000), compact(graphJson == null ? "" : graphJson.toString(), 5000)),
                markdownFallback);
        if (markdown == null || markdown.isBlank()) markdown = markdownFallback;
        markdown = markdown.trim() + sourceAppendix(sources);

        return new ReportResult(irString, markdown);
    }

    /** LLM 的结构化输出不可用时，只复用已核验证据，不引入任何新事实。 */
    private DocumentIr fallbackIr(String title, String evidence, Set<String> validRefs) {
        String anchor = "verified-evidence";
        List<InlineRun> evidenceRuns = evidenceInlines(
                evidence == null || evidence.isBlank() ? "暂无可展示的已核验证据。" : compact(evidence, 8000),
                validRefs);
        Block notice = Block.callout("warning", "结构化报告已降级",
                List.of(Block.paragraph(List.of(new InlineRun(
                        "模型输出未通过结构校验，以下内容仅来自本轮已核验证据。", List.of())))));
        ChapterIr chapter = new ChapterIr("c1", "已核验证据", anchor, 10,
                "本章节由已核验证据安全降级生成。",
                List.of(notice, Block.paragraph(evidenceRuns)));
        Metadata metadata = new Metadata(title, "结构化报告降级视图",
                LocalDateTime.now().toString(), title,
                List.of(new TocEntry(1, chapter.title(), anchor)));
        return new DocumentIr(metadata, List.of(chapter));
    }

    private List<InlineRun> evidenceInlines(String evidence, Set<String> validRefs) {
        List<InlineRun> runs = new ArrayList<>();
        Matcher matcher = SOURCE_REFERENCE.matcher(evidence);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                runs.add(new InlineRun(evidence.substring(cursor, matcher.start()), List.of()));
            }
            String ref = matcher.group(1);
            runs.add(new InlineRun(matcher.group(), validRefs.contains(ref)
                    ? List.of(Mark.source(ref)) : List.of()));
            cursor = matcher.end();
        }
        if (cursor < evidence.length()) runs.add(new InlineRun(evidence.substring(cursor), List.of()));
        if (runs.isEmpty()) runs.add(new InlineRun(evidence, List.of()));
        return runs;
    }

    /** 补全目录：若无 toc 则按章节标题自动生成。 */
    private DocumentIr enrichToc(DocumentIr ir) {
        List<TocEntry> toc = new ArrayList<>();
        for (ChapterIr chapter : ir.chapters()) {
            toc.add(new TocEntry(1, chapter.title(), chapter.anchor()));
        }
        Metadata meta = ir.metadata() == null
                ? new Metadata(null, null, LocalDateTime.now().toString(), null, toc)
                : new Metadata(ir.metadata().title(), ir.metadata().subtitle(),
                LocalDateTime.now().toString(), ir.metadata().query(),
                ir.metadata().toc() == null || ir.metadata().toc().isEmpty() ? toc : ir.metadata().toc());
        return new DocumentIr(meta, ir.chapters());
    }

    private String reportPrompt() {
        return agentConfigs == null ? "你是产业链深度报告 Agent。"
                : agentConfigs.requireEnabled(IndustryAgentConfigService.REPORT_WRITER).systemPrompt();
    }

    private String buildIrPrompt(String title, String evidence, JsonNode graphJson,
                                 List<SearchSource> sources, String forumDigest) {
        String refs = String.join(",", sources.stream().map(SearchSource::sourceRef).toList());
        return """
                你是产业链报告 Agent。基于已核验证据与多 Agent 调研结论，产出严格 JSON 的 Document IR，不要 Markdown 围栏。
                IR schema：{"metadata":{"title","subtitle","toc":[{"level":1,"display","anchor"}]},
                "chapters":[{"chapterId":"c1","anchor":"c1","order":10,"title":"摘要","summary":"","blocks":[...]}]}
                blocks 是一个数组，每个 block 有 type 字段，可选类型与字段：
                - {"type":"heading","level":2,"anchor":"sec-1","text":"章节标题"}
                - {"type":"paragraph","inlines":[{"text":"结论","marks":[{"type":"source","value":"S1"}]}]}
                - {"type":"list","items":[[{"type":"paragraph","inlines":[...]}]]}
                - {"type":"table","rows":{"header":[[{"text":"列名","marks":[]}]],"body":[[...]],"caption":"表格标题"}}
                - {"type":"swotTable","swot":{"strengths":[{"title":"要点","detail":"详情","impact":"高"}],"weaknesses":[...],"opportunities":[...],"threats":[...]}}
                - {"type":"callout","tone":"info|warning|success|danger","text":"标题","items":[[{"type":"paragraph","inlines":[...]}]]}
                - {"type":"hr"}
                inline marks 只允许：bold/italic/code/link/source/color/highlight，其中 source 的 value 必须是来源编号且只能在 {%s} 内。
                段落正文必须放进 inlines，不要写裸 markdown。
                章节建议：摘要、范围与方法、上游、中游、下游、核心企业与竞争、风险、机会与趋势、待核验事项。
                所有关键结论的 source 引用必须命中来源列表，无来源支撑的写「待核验」。

                研究对象：%s
                核验证据：%s
                多 Agent 论坛记录摘要：%s
                """.formatted(refs, title, compact(evidence, 7000), compact(forumDigest, 2000));
    }

    private DocumentIr parseIr(String raw, Set<String> validRefs) {
        try {
            int start = raw == null ? -1 : raw.indexOf('{');
            int end = raw == null ? -1 : raw.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            return objectMapper.treeToValue(node, DocumentIr.class);
        } catch (Exception error) {
            log.debug("IR 解析失败: {}", error.getMessage());
            return null;
        }
    }

    private String writeJson(DocumentIr ir) {
        try {
            return objectMapper.writeValueAsString(ir);
        } catch (Exception error) {
            throw new BizException(500, "报告 IR 序列化失败");
        }
    }

    private String sourceAppendix(List<SearchSource> sources) {
        StringBuilder sb = new StringBuilder("\n\n## 来源\n\n");
        for (SearchSource source : sources) {
            sb.append("- [").append(source.sourceRef()).append("] [")
                    .append(source.title().replace("[", "").replace("]", "")).append("](")
                    .append(source.url()).append(") · ").append(source.publisher()).append('\n');
        }
        return sb.toString();
    }

    private String compact(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    /** 报告构建结果：IR JSON + 降级 Markdown。 */
    public record ReportResult(String irJson, String markdown) {
    }
}



