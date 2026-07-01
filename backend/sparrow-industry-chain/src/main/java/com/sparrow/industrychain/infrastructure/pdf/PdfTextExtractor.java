package com.sparrow.industrychain.infrastructure.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PDF 文本抽取：用户上传的 PDF 论文抽成纯文本，截断为摘要后作为调研来源片段。
 *
 * <p>设计为容错：损坏/加密/非 PDF 一律返回空串，由上层决定是否跳过，
 * 不让单个坏文件中断整个调研流程。
 */
@Component
public class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** 抽取正文上限：再长会被后续 compact 截断，这里先粗截避免无谓内存占用。 */
    private static final int MAX_CHARS = 6000;

    /**
     * 抽取 PDF 全文。
     *
     * @param bytes PDF 原始字节，可为 null
     * @return 纯文本；空、非法或抽取失败时返回空串
     */
    public String extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            return clean(text);
        } catch (Exception error) {
            log.warn("PDF 文本抽取失败，将跳过该文件作为来源: {}", error.getMessage());
            return "";
        }
    }

    private String clean(String value) {
        if (value == null) return "";
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= MAX_CHARS ? collapsed : collapsed.substring(0, MAX_CHARS);
    }
}


