package com.sparrow.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparrow.common.BizException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任意 OpenAI 兼容服务的轻量客户端(/embeddings 与 /chat/completions)。
 * 例如 DashScope compatible-mode、OpenAI、vLLM、Ollama 等。
 */
@Component
public class OpenAiClient {

    private final AiProperties props;
    private final RestClient http;

    public OpenAiClient(AiProperties props) {
        this.props = props;
        this.http = props.llmConfigured()
                ? RestClient.builder()
                    .baseUrl(props.baseUrl())
                    .defaultHeader("Authorization", "Bearer " + props.apiKey())
                    .build()
                : null;
    }

    public boolean configured() {
        return http != null;
    }

    public List<float[]> embed(List<String> texts) {
        requireConfigured();
        List<float[]> result = new ArrayList<>(texts.size());
        // 多数兼容服务对 embeddings 单次 input 数量有限制,按 10 条分批
        for (int i = 0; i < texts.size(); i += 10) {
            List<String> batch = texts.subList(i, Math.min(i + 10, texts.size()));
            JsonNode resp = http.post().uri("/embeddings")
                    .body(Map.of("model", props.embeddingModel(), "input", batch))
                    .retrieve()
                    .body(JsonNode.class);
            for (JsonNode item : resp.get("data")) {
                JsonNode vec = item.get("embedding");
                float[] v = new float[vec.size()];
                for (int j = 0; j < vec.size(); j++) {
                    v[j] = (float) vec.get(j).asDouble();
                }
                result.add(v);
            }
        }
        return result;
    }

    public String chat(String systemPrompt, String userMessage) {
        requireConfigured();
        JsonNode resp = http.post().uri("/chat/completions")
                .body(Map.of(
                        "model", props.chatModel(),
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userMessage))))
                .retrieve()
                .body(JsonNode.class);
        return resp.get("choices").get(0).get("message").get("content").asText();
    }

    private void requireConfigured() {
        if (http == null) {
            throw new BizException(503, "AI 服务未配置");
        }
    }
}
