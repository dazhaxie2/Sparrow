package com.sparrow.industrychain.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * ChatModel 访问门面：封装 LangChain4j ChatModel 的可空性(未配置 AI_API_KEY 时 bean 为 null),
 * 并支持运行时原子热切换(管理端修改模型配置后无需重启)。
 *
 * <p>持有阻塞式 {@link ChatModel} 与流式 {@link StreamingChatModel} 两个引用:
 * <ul>
 *   <li>阻塞模型:规划/证据/图谱/报告等需要完整 JSON 的路径用 {@link #chat(String)} / {@link #chatOr}</li>
 *   <li>流式模型:Agent 总结/反思等文本生成路径用 {@link #stream(String, Consumer, Consumer)},
 *       逐 token 推送给前端;流式不可用时自动回退到阻塞模型(一次性返回)</li>
 * </ul>
 *
 * <p>热切换时整体替换两个引用:新发起的调研取到新模型;进行中的调研已快照旧引用不受影响。
 */
@Component
public class ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatModelProvider.class);

    private final AtomicReference<ChatModel> ref = new AtomicReference<>();
    private final AtomicReference<StreamingChatModel> streamingRef = new AtomicReference<>();

    public ChatModelProvider() {
    }

    /** 由 {@link IndustryChainAiConfig} 在启动时注入初始模型(可能为 null)。 */
    public ChatModelProvider(ChatModel initial) {
        this.ref.set(initial);
    }

    /** 设置初始阻塞模型(供 Config 启动时装配)。 */
    public void init(ChatModel initial) {
        this.ref.set(initial);
    }

    /** 设置初始流式模型(供 Config 启动时装配;可能为 null,此时 stream() 回退到阻塞模型)。 */
    public void initStreaming(StreamingChatModel initial) {
        this.streamingRef.set(initial);
    }

    /** 原子切换为新的阻塞模型实例。 */
    public void swap(ChatModel next) {
        ChatModel prev = ref.getAndSet(next);
        log.info("ChatModel 已热切换: {} -> {}", describe(prev), describe(next));
    }

    /** 原子切换为新的流式模型实例(与阻塞模型同步切换,保证配置一致)。 */
    public void swapStreaming(StreamingChatModel next) {
        StreamingChatModel prev = streamingRef.getAndSet(next);
        log.info("StreamingChatModel 已热切换: {} -> {}",
                prev == null ? "(未配置)" : prev.getClass().getSimpleName(),
                next == null ? "(未配置)" : next.getClass().getSimpleName());
    }

    private String describe(ChatModel model) {
        return model == null ? "(未配置)" : model.getClass().getSimpleName();
    }

    /** 模型是否可用(已配置 AI_API_KEY)。 */
    public boolean available() {
        return ref.get() != null;
    }

    /** 同步对话：模型不可用时抛出,由调用方决定降级文案。 */
    public String chat(String prompt) {
        ChatModel model = ref.get();
        if (model == null) {
            throw new IllegalStateException("AI 服务尚未配置");
        }
        return model.chat(prompt);
    }

    /**
     * 软降级对话：模型未配置 <em>或调用失败</em>(限流/超时/网络异常)时返回 fallback。
     */
    public String chatOr(String prompt, String fallback) {
        ChatModel model = ref.get();
        if (model == null) return fallback;
        try {
            return model.chat(prompt);
        } catch (Exception error) {
            log.warn("ChatModel 调用失败,返回 fallback: {}", error.toString());
            return fallback;
        }
    }

    /**
     * 流式对话:逐 token 调用 onToken,完成后用完整文本调用 onComplete,出错调用 onError。
     *
     * <p>当流式模型未配置或抛错时,回退到阻塞模型一次性返回(把整段文本当作一次 onToken),
     * 保证调用方总能拿到结果,只是失去「逐 token」体验。onToken 的实现应做累加幂等。
     *
     * <p><b>阻塞语义</b>:本方法会阻塞当前线程直到流式完成,便于编排器以顺序风格调用
     * (与 {@link #chat} 一致)。langchain4j 的流式回调在同一线程内同步完成(HTTP 流读完即结束),
     * 因此用 CountDownLatch 等待是安全的。
     *
     * @return 完整文本(便于调用方在流式之外仍持有完整结果)
     */
    public String stream(String prompt, Consumer<String> onToken, Consumer<Throwable> onError) {
        StreamingChatModel streaming = streamingRef.get();
        // 无流式模型:回退阻塞,整段一次性推送。
        if (streaming == null) {
            try {
                String full = chat(prompt);
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
            // 流式失败:回退阻塞模型重试一次,失败则向上抛。
            log.warn("流式对话失败,回退阻塞模型: {}", streamError.toString());
            try {
                String full = chat(prompt);
                onToken.accept(full);
                return full;
            } catch (Exception fallbackError) {
                onError.accept(fallbackError);
                throw fallbackError instanceof RuntimeException re ? re : new RuntimeException(fallbackError);
            }
        }
        return accumulated.toString();
    }

    /** 暴露底层阻塞模型,供需要完整响应的组件使用。 */
    public ChatModel model() {
        return ref.get();
    }
}
