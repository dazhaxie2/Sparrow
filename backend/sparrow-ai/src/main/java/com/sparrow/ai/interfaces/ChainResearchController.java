package com.sparrow.ai.interfaces;

import com.sparrow.ai.application.research.ChainResearchService;
import com.sparrow.ai.application.research.ChainResearchViews.*;
import com.sparrow.ai.infrastructure.research.ChainResearchRepository.SourceInput;
import com.sparrow.ai.infrastructure.research.PdfTextExtractor;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/ai/chain-research")
@Validated
public class ChainResearchController {

    public record AttachmentRequest(@Size(max = 300) String title,
                                    @Size(max = 1200) String url,
                                    @Size(max = 160) String publisher,
                                    @Size(max = 3000) String snippet) {
    }

    public record CardRequest(@NotBlank @Size(max = 120) String title,
                              @Size(max = 2000) String brief,
                              List<AttachmentRequest> sources) {
    }

    public record MessageRequest(@NotBlank @Size(max = 2000) String content) {
    }

    private final ChainResearchService service;
    private final PdfTextExtractor pdfExtractor;

    public ChainResearchController(ChainResearchService service, PdfTextExtractor pdfExtractor) {
        this.service = service;
        this.pdfExtractor = pdfExtractor;
    }

    @GetMapping("/cards")
    public ApiResponse<List<CardSummary>> list() {
        return ApiResponse.ok(service.list(UserContext.require()));
    }

    @PostMapping("/cards")
    public ApiResponse<CardDetail> create(@RequestBody @Valid CardRequest request) {
        return ApiResponse.ok(service.create(UserContext.require(), request.title(), request.brief(),
                toSourceInputs(request.sources())));
    }

    @GetMapping("/cards/{cardId}")
    public ApiResponse<CardDetail> get(@PathVariable long cardId) {
        return ApiResponse.ok(service.get(UserContext.require(), cardId));
    }

    @PutMapping("/cards/{cardId}")
    public ApiResponse<CardDetail> update(@PathVariable long cardId, @RequestBody @Valid CardRequest request) {
        return ApiResponse.ok(service.update(UserContext.require(), cardId, request.title(), request.brief(),
                toSourceInputs(request.sources())));
    }

    @DeleteMapping("/cards/{cardId}")
    public ApiResponse<Void> delete(@PathVariable long cardId) {
        service.delete(UserContext.require(), cardId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/cards/{cardId}/messages")
    public ApiResponse<MessageReply> message(@PathVariable long cardId,
                                             @RequestBody @Valid MessageRequest request) {
        return ApiResponse.ok(service.message(UserContext.require(), cardId, request.content()));
    }

    @PostMapping("/cards/{cardId}/runs")
    public ApiResponse<StartRunResult> start(@PathVariable long cardId) {
        return ApiResponse.ok(service.start(UserContext.require(), cardId));
    }

    @GetMapping("/cards/{cardId}/runs/{runId}")
    public ApiResponse<RunView> run(@PathVariable long cardId, @PathVariable long runId) {
        return ApiResponse.ok(service.run(UserContext.require(), cardId, runId));
    }

    @PostMapping("/cards/{cardId}/runs/{runId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long cardId, @PathVariable long runId) {
        service.cancel(UserContext.require(), cardId, runId);
        return ApiResponse.ok(null);
    }

    @GetMapping(value = "/cards/{cardId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long cardId) {
        return service.subscribe(UserContext.require(), cardId);
    }

    /** PDF 上传：抽文本后作为附件追加到卡片，来源编号由服务端自动分配。 */
    @PostMapping(value = "/cards/{cardId}/attachments/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CardDetail> uploadAttachment(@PathVariable long cardId,
                                                    @RequestParam("file") MultipartFile file) {
        long userId = UserContext.require();
        if (file == null || file.isEmpty()) throw new BizException(400, "上传文件为空");
        String original = file.getOriginalFilename();
        String name = original == null || original.isBlank() ? "未命名文档.pdf" : original;
        String snippet;
        try {
            snippet = pdfExtractor.extract(file.getBytes());
        } catch (IOException error) {
            throw new BizException(400, "读取上传文件失败");
        }
        if (snippet.isBlank()) throw new BizException(422, "无法从该文件中抽取有效文本，请确认是文本型 PDF");
        int nextIndex = service.attachmentCount(userId, cardId) + 1;
        SourceInput attachment = new SourceInput("S" + nextIndex, name,
                "uploaded:" + name, "用户上传 PDF", snippet);
        return ApiResponse.ok(service.addAttachment(userId, cardId, attachment));
    }

    private List<SourceInput> toSourceInputs(List<AttachmentRequest> sources) {
        if (sources == null) return List.of();
        return sources.stream().map(item -> new SourceInput(null, item.title(), item.url(),
                item.publisher(), item.snippet())).toList();
    }
}
