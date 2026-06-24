package com.sparrow.ai.interfaces;

import com.sparrow.ai.application.research.ChainResearchService;
import com.sparrow.ai.application.research.ChainResearchViews.*;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/ai/chain-research")
@Validated
public class ChainResearchController {

    public record CardRequest(@NotBlank @Size(max = 120) String title,
                              @Size(max = 2000) String brief) {
    }

    public record MessageRequest(@NotBlank @Size(max = 2000) String content) {
    }

    private final ChainResearchService service;

    public ChainResearchController(ChainResearchService service) {
        this.service = service;
    }

    @GetMapping("/cards")
    public ApiResponse<List<CardSummary>> list() {
        return ApiResponse.ok(service.list(UserContext.require()));
    }

    @PostMapping("/cards")
    public ApiResponse<CardDetail> create(@RequestBody @Valid CardRequest request) {
        return ApiResponse.ok(service.create(UserContext.require(), request.title(), request.brief()));
    }

    @GetMapping("/cards/{cardId}")
    public ApiResponse<CardDetail> get(@PathVariable long cardId) {
        return ApiResponse.ok(service.get(UserContext.require(), cardId));
    }

    @PutMapping("/cards/{cardId}")
    public ApiResponse<CardDetail> update(@PathVariable long cardId, @RequestBody @Valid CardRequest request) {
        return ApiResponse.ok(service.update(UserContext.require(), cardId, request.title(), request.brief()));
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
}
