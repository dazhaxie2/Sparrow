package com.sparrow.industrychain.application.source;

import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.application.card.ResearchCardService;
import com.sparrow.industrychain.application.card.ResearchCardViews.CardDetail;
import com.sparrow.industrychain.infrastructure.pdf.PdfTextExtractor;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.SourceInput;
import org.springframework.stereotype.Service;

@Service
public class AttachmentService {

    private final PdfTextExtractor pdfExtractor;
    private final ResearchCardService cardService;
    private final SourceRefAllocator sourceRefs;

    public AttachmentService(PdfTextExtractor pdfExtractor, ResearchCardService cardService,
                             SourceRefAllocator sourceRefs) {
        this.pdfExtractor = pdfExtractor;
        this.cardService = cardService;
        this.sourceRefs = sourceRefs;
    }

    public CardDetail uploadPdf(long userId, long cardId, String originalFilename, byte[] bytes) {
        String snippet = pdfExtractor.extract(bytes);
        if (snippet.isBlank()) throw new BizException(422, "无法从该文件中抽取有效文本，请确认是文本型 PDF");
        String name = originalFilename == null || originalFilename.isBlank() ? "未命名文档.pdf" : originalFilename;
        String sourceRef = sourceRefs.nextAfter(cardService.attachmentCount(userId, cardId));
        SourceInput attachment = new SourceInput(sourceRef, name, "uploaded:" + name, "用户上传 PDF", snippet);
        return cardService.addAttachment(userId, cardId, attachment);
    }
}
