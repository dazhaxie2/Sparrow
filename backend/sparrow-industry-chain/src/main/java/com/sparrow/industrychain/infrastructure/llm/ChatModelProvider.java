package com.sparrow.industrychain.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 访问门面：封装 LangChain4j ChatModel 的可空性(未配置 AI_API_KEY 时 bean 为 null),
 * 并支持运行时原子热切换(管理端修改模型配置后无需重启)。
 *
 * <p>持有 {@link AtomicReference},切换时整体替换引用:
 * <ul>
 *   <li>新发起的调研取到新模型</li>
 *   <li>进行中的调研在启动时已通过 {@link #model()} 拿到旧引用并传入各 Agent,自然继续用旧模型</li>
 * </ul>
 *
 * <p>让 Multi-Agent / 报告 Builder 等组件通过统一接口获取模型,避免到处重复 null 检查。
 */
@Component
public class ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatModelProvider.class);

    private final AtomicReference<ChatModel> ref = new AtomicReference<>();

    public ChatModelProvider() {
    }

    /** 由 {@link IndustryChainAiConfig} 在启动时注入初始模型(可能为 null)。 */
    public ChatModelProvider(ChatModel initial) {
        this.ref.set(initial);
    }

    /** 设置初始模型(供 Config 启动时装配)。 */
    public void init(ChatModel initial) {
        this.ref.set(initial);
    }

    /** 原子切换为新的模型实例。 */
    public void swap(ChatModel next) {
        ChatModel prev = ref.getAndSet(next);
        log.info("ChatModel 已热切换: {} -> {}", describe(prev), describe(next));
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
     *
     * <p>用于规划 Agent 等面向用户的同步路径——云端 LLM 触发速率限制(HttpException 1302)
     * 不应冒泡成 HTTP 500,而应降级为可控的提示文案,与 {@code ResearchRunner} 的兜底风格一致。
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

    /** 暴露底层模型,供需要工具/流式等高级特性的组件使用。 */
    public ChatModel model() {
        return ref.get();
    }
}
