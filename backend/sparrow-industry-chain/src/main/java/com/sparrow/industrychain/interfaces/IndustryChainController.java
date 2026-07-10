package com.sparrow.industrychain.interfaces;

import com.sparrow.industrychain.application.card.ResearchCardService;
import com.sparrow.industrychain.application.card.ResearchCardViews.*;
import com.sparrow.industrychain.application.source.AttachmentService;
import com.sparrow.industrychain.infrastructure.persistence.IndustryChainRepository.SourceInput;
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
@RequestMapping("/api/chains")
@Validated
public class IndustryChainController {

    /** 附件请求：用户提交的结构化来源（标题、URL、发布者、摘要）。 */
    public record AttachmentRequest(@Size(max = 300) String title,
                                    @Size(max = 1200) String url,
                                    @Size(max = 160) String publisher,
                                    @Size(max = 3000) String snippet) {
    }

    /** 卡片请求：标题、简述与可选的资料来源列表。 */
    public record CardRequest(@NotBlank @Size(max = 120) String title,
                              @Size(max = 2000) String brief,
                              List<AttachmentRequest> sources) {
    }

    /** 消息请求：用户对话内容。 */
    public record MessageRequest(@NotBlank @Size(max = 2000) String content) {
    }

    private final ResearchCardService service;
    private final AttachmentService attachmentService;

    public IndustryChainController(ResearchCardService service, AttachmentService attachmentService) {
        this.service = service;
        this.attachmentService = attachmentService;
    }

    /** 查询用户的所有产业链调研卡片列表。 */
    @GetMapping("/cards")
    public ApiResponse<List<CardSummary>> list() {
        return ApiResponse.ok(service.list(UserContext.require()));
    }

    /** 创建新的产业链调研卡片，可选附带资料来源。 */
    @PostMapping("/cards")
    public ApiResponse<CardDetail> create(@RequestBody @Valid CardRequest request) {
        return ApiResponse.ok(service.create(UserContext.require(), request.title(), request.brief(),
                toSourceInputs(request.sources())));
    }

    /** 获取产业链调研卡片详情。 */
    @GetMapping("/cards/{cardId}")
    public ApiResponse<CardDetail> get(@PathVariable long cardId) {
        return ApiResponse.ok(service.get(UserContext.require(), cardId));
    }

    /** 更新产业链调研卡片信息与资料来源。 */
    @PutMapping("/cards/{cardId}")
    public ApiResponse<CardDetail> update(@PathVariable long cardId, @RequestBody @Valid CardRequest request) {
        return ApiResponse.ok(service.update(UserContext.require(), cardId, request.title(), request.brief(),
                toSourceInputs(request.sources())));
    }

    /** 删除产业链调研卡片。 */
    @DeleteMapping("/cards/{cardId}")
    public ApiResponse<Void> delete(@PathVariable long cardId) {
        service.delete(UserContext.require(), cardId);
        return ApiResponse.ok(null);
    }

    /** 发送用户消息，获取规划 Agent 回复。 */
    @PostMapping("/cards/{cardId}/messages")
    public ApiResponse<MessageReply> message(@PathVariable long cardId,
                                             @RequestBody @Valid MessageRequest request) {
        return ApiResponse.ok(service.message(UserContext.require(), cardId, request.content()));
    }

    /** 启动深度调研任务。 */
    @PostMapping("/cards/{cardId}/runs")
    public ApiResponse<StartRunResult> start(@PathVariable long cardId) {
        return ApiResponse.ok(service.start(UserContext.require(), cardId));
    }

    /** 从最近失败任务的检查点继续，复用原 runId。 */
    @PostMapping("/cards/{cardId}/runs/resume")
    public ApiResponse<ResumeRunResult> resume(@PathVariable long cardId) {
        return ApiResponse.ok(service.resume(UserContext.require(), cardId));
    }

    /** 查询调研任务运行状态。 */
    @GetMapping("/cards/{cardId}/runs/{runId}")
    public ApiResponse<RunView> run(@PathVariable long cardId, @PathVariable long runId) {
        return ApiResponse.ok(service.run(UserContext.require(), cardId, runId));
    }

    /** 取消运行中的调研任务。 */
    @PostMapping("/cards/{cardId}/runs/{runId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable long cardId, @PathVariable long runId) {
        service.cancel(UserContext.require(), cardId, runId);
        return ApiResponse.ok(null);
    }

    /** 订阅调研进度 SSE 事件流。 */
    @GetMapping(value = "/cards/{cardId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long cardId) {
        return service.subscribe(UserContext.require(), cardId);
    }

    /** 查询卡片最近一次运行的论坛事件(用于工作台初次加载还原协作流)。 */
    @GetMapping("/cards/{cardId}/forum")
    public ApiResponse<List<ForumEventView>> forum(@PathVariable long cardId) {
        return ApiResponse.ok(service.forumEvents(UserContext.require(), cardId));
    }

    /** PDF 上传：抽文本后作为附件追加到卡片，来源编号由服务端自动分配。 */
    @PostMapping(value = "/cards/{cardId}/attachments/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CardDetail> uploadAttachment(@PathVariable long cardId,
                                                    @RequestParam("file") MultipartFile file) {
        long userId = UserContext.require();
        if (file == null || file.isEmpty()) throw new BizException(400, "上传文件为空");
        String original = file.getOriginalFilename();
        String name = original == null || original.isBlank() ? "未命名文档.pdf" : original;
        try {
            return ApiResponse.ok(attachmentService.uploadPdf(userId, cardId, name, file.getBytes()));
        } catch (IOException error) {
            throw new BizException(400, "读取上传文件失败");
        }
    }

    /** 将附件请求列表转为 SourceInput，供 Service 层使用。 */
    private List<SourceInput> toSourceInputs(List<AttachmentRequest> sources) {
        if (sources == null) return List.of();
        return sources.stream().map(item -> new SourceInput(null, item.title(), item.url(),
                item.publisher(), item.snippet())).toList();
    }
}

