package com.sparrow.industrychain.infrastructure.pdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void returnsEmptyForNullInput() {
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyBytes() {
        assertThat(extractor.extract(new byte[0])).isEmpty();
    }

    @Test
    void returnsEmptyForNonPdfBytes() {
        // 纯文本字节，非合法 PDF，应被容错吞掉而非抛异常
        byte[] notPdf = "这是一段纯文本，不是 PDF。".getBytes();
        assertThat(extractor.extract(notPdf)).isEmpty();
    }
}


