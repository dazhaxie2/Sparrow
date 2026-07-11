package com.sparrow.industrychain.infrastructure.config;

import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IndustryChainAiConfig 启动引导(bootstrap)单测。
 *
 * <p>聚焦新增行为:DB 为空 + env 已配置 + 主密钥就绪时,把 env 配置导入 model_config 并激活,
 * 让管理端配置页可见可管。不依赖真实 LLM 连接(buildFrom 构造的模型对象只被喂给 mock provider)。
 */
class IndustryChainAiConfigBootstrapTest {

    private ModelConfigRepository repository;
    private ChatModelProvider provider;
    private IndustryChainProperties props;
    private IndustryChainAiConfig aiConfig;

    @BeforeEach
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        provider = mock(ChatModelProvider.class);
        // env 已配置 GLM
        props = new IndustryChainProperties(
                "https://open.bigmodel.cn/api/paas/v4", "sk-glm-test", "glm-4.7",
                45, 1, 1, 10, "https://search.example");
        aiConfig = new IndustryChainAiConfig(provider, repository, props);
    }

    /** DB 无激活配置、表为空、env 已配置、主密钥就绪 → 导入 env 为 active 记录。 */
    @Test
    void bootstrapsEnvConfigWhenTableEmptyAndEncryptionReady() {
        // findActiveDecrypted 被调两次:首次(表空)返回空,导入后返回刚写入的记录
        ModelConfig seed = ModelConfig.decrypted(
                7L, "环境变量引导配置", props.baseUrl(), props.chatModel(),
                "sk-glm-test", 3000, 45, 1, true);
        when(repository.findActiveDecrypted()).thenReturn(Optional.empty(), Optional.of(seed));
        when(repository.count()).thenReturn(0);
        when(repository.encryptionReady()).thenReturn(true);
        when(repository.encryptApiKey("sk-glm-test")).thenReturn("ENC:glm");
        when(repository.insertActive(any(), any(), any(), eq("ENC:glm"), anyInt(), anyInt(), anyInt()))
                .thenReturn(7L);

        aiConfig.initModel();

        // 关键断言:env 配置被加密导入为 active 记录(insertActive 内部负责 setActive)
        verify(repository).encryptApiKey("sk-glm-test");
        verify(repository).insertActive(
                eq("环境变量引导配置"), eq(props.baseUrl()), eq(props.chatModel()),
                eq("ENC:glm"), eq(3000), eq(45), eq(1));
        // 用 DB 记录(而非纯内存 env)建模型并 init
        verify(provider).init(any());
        verify(provider).initStreaming(any());
    }

    /** 表已有数据(管理员配过) → 不触发引导,不重复导入。 */
    @Test
    void doesNotBootstrapWhenTableNotEmpty() {
        when(repository.findActiveDecrypted()).thenReturn(Optional.of(ModelConfig.decrypted(
                1L, "已有配置", props.baseUrl(), props.chatModel(),
                "sk-existing", 3000, 45, 1, true)));

        aiConfig.initModel();

        verify(repository, never()).count();
        verify(repository, never()).insertActive(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        verify(repository, never()).setActive(anyLong());
    }

    /** 表空但未配置主密钥 → 跳过导入(避免明文落库),回退到内存 env 模型。 */
    @Test
    void skipsBootstrapWhenEncryptionNotReady() {
        when(repository.findActiveDecrypted()).thenReturn(Optional.empty());
        when(repository.count()).thenReturn(0);
        when(repository.encryptionReady()).thenReturn(false);

        aiConfig.initModel();

        verify(repository, never()).insertActive(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        verify(repository, never()).encryptApiKey(any());
        // 回退:仍用 env 建内存模型
        verify(provider).init(any());
    }
}
