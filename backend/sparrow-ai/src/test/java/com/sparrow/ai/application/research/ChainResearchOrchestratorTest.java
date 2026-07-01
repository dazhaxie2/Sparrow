package com.sparrow.ai.application.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.ai.application.research.ir.Block;
import com.sparrow.ai.application.research.ir.Block.SwotTable;
import com.sparrow.ai.application.research.ir.ChapterIr;
import com.sparrow.ai.application.research.ir.DocumentIr;
import com.sparrow.ai.application.research.ir.DocumentIr.Metadata;
import com.sparrow.ai.application.research.ir.DocumentIr.TocEntry;
import com.sparrow.ai.application.research.ir.InlineRun;
import com.sparrow.ai.application.research.ir.InlineRun.Mark;
import com.sparrow.ai.application.research.ir.IrValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Document IR 校验器测试：验证富报告中间表示的来源引用合法性——
 * 所有 [Sx] 引用必须命中实际来源列表，这是守住事实边界、防止 LLM 编造引用的关键防线。
 */
class ChainResearchOrchestratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IrValidator validator = new IrValidator();

    /** 合法引用：所有 source mark 命中来源列表 → 校验通过(错误列表为空)。 */
    @Test
    void passesWhenAllSourceReferencesAreValid() {
        DocumentIr ir = irWithParagraph(Mark.source("S1"), Mark.source("S2"));
        List<String> errors = validator.validate(ir, Set.of("S1", "S2"));
        assertThat(errors).isEmpty();
    }

    /** 非法引用：出现来源列表外的 [S9] → 校验失败并报出该引用。 */
    @Test
    void flagsSourceReferencesNotInSourceList() {
        DocumentIr ir = irWithParagraph(Mark.source("S1"), Mark.source("S9"));
        List<String> errors = validator.validate(ir, Set.of("S1"));
        assertThat(errors).anyMatch(msg -> msg.contains("S9"));
    }

    /** 空 IR / 空章节 → 校验失败。 */
    @Test
    void rejectsEmptyDocument() {
        assertThat(validator.validate(null, Set.of("S1"))).isNotEmpty();
        DocumentIr empty = new DocumentIr(null, List.of());
        assertThat(validator.validate(empty, Set.of("S1"))).isNotEmpty();
    }

    /** 目录锚点必须命中真实章节，否则报错。 */
    @Test
    void flagsTocAnchorMissingChapter() {
        Metadata meta = new Metadata("标题", null, "now", null,
                List.of(new TocEntry(1, "不存在的章节", "ghost-anchor")));
        ChapterIr chapter = new ChapterIr("c1", "摘要", "c1", 10, null,
                List.of(Block.paragraph(List.of(new InlineRun("文本", List.of(Mark.source("S1")))))));
        DocumentIr ir = new DocumentIr(meta, List.of(chapter));
        List<String> errors = validator.validate(ir, Set.of("S1"));
        assertThat(errors).anyMatch(msg -> msg.contains("ghost-anchor"));
    }

    /** IR 可被 Jackson 正常序列化/反序列化（落库与前端契约）。 */
    @Test
    void serializesRoundTrip() throws Exception {
        Block swot = Block.swot(new SwotTable(
                List.of(new Block.SwotItem("优势", "详情", "高")),
                List.of(), List.of(), List.of()));
        ChapterIr chapter = new ChapterIr("c1", "SWOT", "c1", 10, null, List.of(swot));
        DocumentIr ir = new DocumentIr(new Metadata("t", null, "now", null, null), List.of(chapter));
        String json = MAPPER.writeValueAsString(ir);
        DocumentIr back = MAPPER.readValue(json, DocumentIr.class);
        assertThat(back.chapters()).hasSize(1);
        assertThat(back.chapters().get(0).blocks().get(0).type()).isEqualTo(Block.SWOT);
    }

    private DocumentIr irWithParagraph(Mark... marks) {
        InlineRun run = new InlineRun("结论文本", List.of(marks));
        ChapterIr chapter = new ChapterIr("c1", "摘要", "c1", 10, null,
                List.of(Block.paragraph(List.of(run))));
        return new DocumentIr(new Metadata("标题", null, "now", null,
                List.of(new TocEntry(1, "摘要", "c1"))), List.of(chapter));
    }
}
