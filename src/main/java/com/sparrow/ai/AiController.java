package com.sparrow.ai;

import com.sparrow.ai.AiService.AskResult;
import com.sparrow.common.ApiResponse;
import com.sparrow.common.UserContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@Validated
public class AiController {

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/ask")
    public ApiResponse<AskResult> ask(@RequestBody @Validated AskRequest req) {
        return ApiResponse.ok(aiService.ask(UserContext.require(), req.question()));
    }
}
