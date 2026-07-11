package com.sparrow.industrychain.application.report.ir;

import com.sparrow.industrychain.application.report.ir.Block.PestTable;
import com.sparrow.industrychain.application.report.ir.Block.SwotTable;
import com.sparrow.industrychain.application.report.ir.Block.TableRows;
import com.sparrow.industrychain.application.report.ir.Block.Widget;
import com.sparrow.industrychain.application.report.ir.InlineRun.Mark;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Document IR 校验器。
 *
 * <p>对照 BettaFish ReportEngine 的 {@code IRValidator}：在 LLM 产出 IR 后、落库与渲染前进行结构校验，
 * 守住事实边界——所有 {@code source} 内联标记的来源编号必须命中实际来源列表，避免 LLM 编造引用。
 * 校验失败由调用方触发「修复重试」或回退降级 Markdown。
 */
@Component
public class IrValidator {

    /** 校验整份 IR：章节非空、目录锚点存在、所有来源引用合法。返回错误列表，空表示通过。 */
    public List<String> validate(DocumentIr ir, Set<String> validSourceRefs) {
        Set<String> errors = new HashSet<>();
        if (ir == null || ir.chapters() == null || ir.chapters().isEmpty()) {
            errors.add("报告章节为空");
            return List.copyOf(errors);
        }
        Set<String> anchors = new HashSet<>();
        for (ChapterIr chapter : ir.chapters()) {
            if (chapter.anchor() == null || chapter.anchor().isBlank()) {
                errors.add("章节缺少 anchor: " + chapter.title());
            }
            if (chapter.blocks() == null || chapter.blocks().isEmpty()) {
                errors.add("章节无正文块: " + chapter.title());
            } else {
                validateBlocks(chapter.blocks(), validSourceRefs, errors);
            }
            anchors.add(chapter.anchor());
        }
        // 目录锚点必须命中真实章节
        if (ir.metadata() != null && ir.metadata().toc() != null) {
            for (DocumentIr.TocEntry entry : ir.metadata().toc()) {
                if (entry.anchor() != null && !anchors.contains(entry.anchor())) {
                    errors.add("目录锚点未命中章节: " + entry.anchor());
                }
            }
        }
        return List.copyOf(errors);
    }

    private void validateBlocks(List<Block> blocks, Set<String> validRefs, Set<String> errors) {
        for (Block block : blocks) {
            if (block == null || block.type() == null) {
                errors.add("存在空块或缺少 type");
                continue;
            }
            switch (block.type()) {
                case Block.PARAGRAPH, Block.BLOCKQUOTE -> validateInlines(block.inlines(), validRefs, errors);
                case Block.LIST -> {
                    if (block.items() != null) block.items().forEach(item -> {
                        if (item != null) validateBlocks(item, validRefs, errors);
                    });
                }
                case Block.CALLOUT -> {
                    if (block.items() != null) block.items().forEach(item -> {
                        if (item != null) validateBlocks(item, validRefs, errors);
                    });
                }
                case Block.TABLE -> validateTable(block.rows(), validRefs, errors);
                case Block.SWOT -> validateSwot(block.swot(), validRefs, errors);
                case Block.PEST -> validatePest(block.pest(), validRefs, errors);
                case Block.WIDGET -> validateWidget(block.widget(), errors);
                case Block.HEADING, Block.KPI, Block.HR -> { /* 无来源引用 */ }
                default -> errors.add("不支持的块类型: " + block.type());
            }
        }
    }

    private void validateInlines(List<InlineRun> inlines, Set<String> validRefs, Set<String> errors) {
        if (inlines == null) return;
        for (InlineRun run : inlines) {
            if (run.marks() == null) continue;
            for (Mark mark : run.marks()) {
                if ("source".equals(mark.type()) && !validRefs.contains(mark.value())) {
                    errors.add("来源引用未命中: " + mark.value());
                }
            }
        }
    }

    private void validateTable(TableRows rows, Set<String> validRefs, Set<String> errors) {
        if (rows == null) return;
        // header/body 为「行 → 单元格 → inline 片段」的三层结构
        if (rows.header() != null) rows.header().forEach(row -> {
            if (row != null) row.forEach(cell -> validateInlines(cell, validRefs, errors));
        });
        if (rows.body() != null) rows.body().forEach(row -> {
            if (row != null) row.forEach(cell -> validateInlines(cell, validRefs, errors));
        });
    }

    private void validateSwot(SwotTable swot, Set<String> validRefs, Set<String> errors) {
        if (swot == null) return;
        validateSwotItems(swot.strengths(), errors);
        validateSwotItems(swot.weaknesses(), errors);
        validateSwotItems(swot.opportunities(), errors);
        validateSwotItems(swot.threats(), errors);
    }

    private void validateSwotItems(List<Block.SwotItem> items, Set<String> errors) {
        if (items == null) return;
        for (Block.SwotItem item : items) {
            if (item == null || item.title() == null || item.title().isBlank()) {
                errors.add("SWOT 项缺少标题");
            }
        }
    }

    private void validatePest(PestTable pest, Set<String> validRefs, Set<String> errors) {
        if (pest == null) return;
        validatePestItems(pest.political(), errors);
        validatePestItems(pest.economic(), errors);
        validatePestItems(pest.social(), errors);
        validatePestItems(pest.technological(), errors);
    }

    private void validatePestItems(List<Block.PestItem> items, Set<String> errors) {
        if (items == null) return;
        for (Block.PestItem item : items) {
            if (item == null || item.title() == null || item.title().isBlank()) {
                errors.add("PEST 项缺少标题");
            }
        }
    }

    private void validateWidget(Widget widget, Set<String> errors) {
        if (widget == null || widget.widgetId() == null || widget.widgetType() == null) {
            errors.add("图表块缺少 widgetId/widgetType");
        }
    }

}
