package com.sparrow.industrychain.infrastructure.llm;

import com.sparrow.common.ai.model.ModelScene;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * ChatModel 访问门面：封装 LangChain4j ChatModel 的可空性(未配置 AI_API_KEY 时 bean 为 null),
 * 并支持运行时原子热切换(管理端修改模型配置后无需重启)。
 *
 * <p>模型池场景化:按 {@link ModelScene} 持有多组(阻塞 + 流式)模型引用,每个场景独立装配、
 * 独立热切换。无场景参数的旧方法(如 {@link #chat})默认走 {@link ModelScene#CHAIN_PLANNING},
 * 便于消费方渐进迁移。</p>
 *
 * <p>热切换时整体替换该场景的两个引用:新发起的调研取到新模型;进行中的调研已快照旧引用不受影响。</p>
 */
@Component
public class ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatModelProvider.class);

    /** 每个场景一对(阻塞 + 流式)引用。 */
    private final Map<ModelScene, AtomicReference<ChatModel>> chatRefs = new EnumMap<>(ModelScene.class);
    private final Map<ModelScene, AtomicReference<StreamingChatModel>> streamingRefs = new EnumMap<>(ModelScene.class);

    public ChatModelProvider() {
        for (ModelScene scene : ModelScene.values()) {
            chatRefs.put(scene, new AtomicReference<>());
            streamingRefs.put(scene, new AtomicReference<>());
        }
    }

    private AtomicReference<ChatModel> chatRef(ModelScene scene) {
        return chatRefs.get(scene);
    }

    private AtomicReference<StreamingChatModel> streamingRef(ModelScene scene) {
        return streamingRefs.get(scene);
    }

    // ── 启动装配(init)──

    /** 设置指定场景的初始阻塞模型(供 Config 启动时装配)。 */
    public void init(ModelScene scene, ChatModel initial) {
        chatRef(scene).set(initial);
    }

    /** 设置指定场景的初始流式模型。 */
    public void initStreaming(ModelScene scene, StreamingChatModel initial) {
        streamingRef(scene).set(initial);
    }

    // ── 热切换(swap)──

    /** 原子切换指定场景的阻塞模型。 */
    public void swap(ModelScene scene, ChatModel next) {
        ChatModel prev = chatRef(scene).getAndSet(next);
        log.info("ChatModel[{}] 已热切换: {} -> {}", scene.dbValue(), describe(prev), describe(next));
    }

    /** 原子切换指定场景的流式模型(与阻塞模型同步切换,保证配置一致)。 */
    public void swapStreaming(ModelScene scene, StreamingChatModel next) {
        StreamingChatModel prev = streamingRef(scene).getAndSet(next);
        log.info("StreamingChatModel[{}] 已热切换: {} -> {}", scene.dbValue(),
                prev == null ? "(未配置)" : prev.getClass().getSimpleName(),
                next == null ? "(未配置)" : next.getClass().getSimpleName());
    }

    private String describe(ChatModel model) {
        return model == null ? "(未配置)" : model.getClass().getSimpleName();
    }

    // ── 场景感知访问(推荐消费方使用)──

    /** 指定场景的模型是否可用。 */
    public boolean available(ModelScene scene) {
        return chatRef(scene).get() != null;
    }

    /** 指定场景同步对话:模型不可用时抛出。 */
    public String chat(ModelScene scene, String prompt) {
        ChatModel model = chatRef(scene).get();
        if (model == null) {
            throw new IllegalStateException("AI 服务尚未配置: " + scene.dbValue());
        }
        return model.chat(prompt);
    }

    /** 指定场景软降级对话:模型未配置或调用失败时返回 fallback。 */
    public String chatOr(ModelScene scene, String prompt, String fallback) {
        ChatModel model = chatRef(scene).get();
        if (model == null) return fallback;
        try {
            return model.chat(prompt);
        } catch (Exception error) {
            log.warn("ChatModel[{}] 调用失败,返回 fallback: {}", scene.dbValue(), error.toString());
            return fallback;
        }
    }

    /** 指定场景流式对话:逐 token 调用 onToken,出错调用 onError,流式不可用时回退阻塞。 */
    public String stream(ModelScene scene, String prompt, Consumer<String> onToken, Consumer<Throwable> onError) {
        StreamingChatModel streaming = streamingRef(scene).get();
        if (streaming == null) {
            try {
                String full = chat(scene, prompt);
                onToken.accept(full);
                return full;
            } catch (Exception error) {
                onError.accept(error);
                throw error instanceof RuntimeException re ? re : new RuntimeException(error);
            }
        }
        StringBuilder accumulated = new StringBuilder();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        streaming.chat(prompt, new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse == null || partialResponse.isEmpty()) return;
                accumulated.append(partialResponse);
                onToken.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("流式对话被中断", ie);
        }
        Throwable streamError = errorRef.get();
        if (streamError != null) {
            log.warn("流式对话[{}]失败,回退阻塞模型: {}", scene.dbValue(), streamError.toString());
            try {
                String full = chat(scene, prompt);
                onToken.accept(full);
                return full;
            } catch (Exception fallbackError) {
                onError.accept(fallbackError);
                throw fallbackError instanceof RuntimeException re ? re : new RuntimeException(fallbackError);
            }
        }
        return accumulated.toString();
    }

    /** 暴露指定场景的底层阻塞模型,供需要完整响应的组件使用。 */
    public ChatModel model(ModelScene scene) {
        return chatRef(scene).get();
    }

    // ── 无场景重载(默认 CHAIN_PLANNING,向后兼容,便于消费方渐进迁移)──

    public boolean available() { return available(ModelScene.CHAIN_PLANNING); }
    public String chat(String prompt) { return chat(ModelScene.CHAIN_PLANNING, prompt); }
    public String chatOr(String prompt, String fallback) { return chatOr(ModelScene.CHAIN_PLANNING, prompt, fallback); }
    public String stream(String prompt, Consumer<String> onToken, Consumer<Throwable> onError) {
        return stream(ModelScene.CHAIN_PLANNING, prompt, onToken, onError);
    }
    public ChatModel model() { return model(ModelScene.CHAIN_PLANNING); }

    /** 旧启动装配重载(默认 CHAIN_PLANNING)。 */
    public void init(ChatModel initial) { init(ModelScene.CHAIN_PLANNING, initial); }
    public void initStreaming(StreamingChatModel initial) { initStreaming(ModelScene.CHAIN_PLANNING, initial); }

    /** 旧热切换重载(默认 CHAIN_PLANNING)。 */
    public void swap(ChatModel next) { swap(ModelScene.CHAIN_PLANNING, next); }
    public void swapStreaming(StreamingChatModel next) { swapStreaming(ModelScene.CHAIN_PLANNING, next); }
}
