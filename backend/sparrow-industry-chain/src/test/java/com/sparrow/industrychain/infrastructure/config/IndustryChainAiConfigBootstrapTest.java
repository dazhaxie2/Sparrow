package com.sparrow.industrychain.infrastructure.config;

import com.sparrow.common.ai.model.ModelKind;
import com.sparrow.common.ai.model.ModelScene;
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
import static org.mockito.Mockito.times;
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

    /** DB 无激活配置、表为空、env 已配置、主密钥就绪 → 把 env 配置导入为各场景的 active 记录。 */
    @Test
    void bootstrapsEnvConfigWhenTableEmptyAndEncryptionReady() {
        // 装配阶段:findActiveDecrypted 返回刚写入的记录(每个场景一条,apiKey 非空 → 走 DB 建模型路径)
        ModelConfig seed = ModelConfig.decrypted(
                7L, "环境变量引导配置", props.baseUrl(), props.chatModel(),
                "sk-glm-test", 3000, 45, 1, true,
                ModelScene.CHAIN_PLANNING, ModelKind.CHAT);
        when(repository.findActiveDecrypted(any())).thenReturn(Optional.of(seed));
        when(repository.count()).thenReturn(0);
        when(repository.encryptionReady()).thenReturn(true);
        when(repository.encryptApiKey("sk-glm-test")).thenReturn("ENC:glm");
        when(repository.insertActive(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
                any(ModelScene.class), any(ModelKind.class))).thenReturn(7L);

        aiConfig.initModel();

        // 关键断言:env 配置被加密导入为 4 个场景的 active 记录(insertActive 内部负责 setActiveByScene)
        verify(repository).encryptApiKey("sk-glm-test");
        verify(repository, times(4)).insertActive(
                any(), eq(props.baseUrl()), eq(props.chatModel()),
                eq("ENC:glm"), eq(3000), eq(45), eq(1),
                any(ModelScene.class), eq(ModelKind.CHAT));
        // 各场景用 DB 记录建模型并 init
        verify(provider, times(4)).init(any(ModelScene.class), any());
        verify(provider, times(4)).initStreaming(any(ModelScene.class), any());
    }

    /** 表已有数据(管理员配过) → 不触发引导,不重复导入。 */
    @Test
    void doesNotBootstrapWhenTableNotEmpty() {
        // 表非空 → needBootstrap=false → 不走 seedScenesFromEnv;各场景直接用已有 DB 配置装配
        when(repository.count()).thenReturn(1);
        when(repository.findActiveDecrypted(any())).thenReturn(Optional.of(ModelConfig.decrypted(
                1L, "已有配置", props.baseUrl(), props.chatModel(),
                "sk-existing", 3000, 45, 1, true,
                ModelScene.CHAIN_PLANNING, ModelKind.CHAT)));

        aiConfig.initModel();

        verify(repository, never()).encryptApiKey(any());
        verify(repository, never()).insertActive(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
                any(ModelScene.class), any(ModelKind.class));
        verify(repository, never()).setActiveByScene(anyLong(), any());
        // 仍用已有 DB 配置建模型装配(4 个场景)
        verify(provider, times(4)).init(any(ModelScene.class), any());
    }

    /** 表空但未配置主密钥 → 跳过导入(避免明文落库),回退到内存 env 模型。 */
    @Test
    void skipsBootstrapWhenEncryptionNotReady() {
        when(repository.findActiveDecrypted(any())).thenReturn(Optional.empty());
        when(repository.count()).thenReturn(0);
        when(repository.encryptionReady()).thenReturn(false);

        aiConfig.initModel();

        verify(repository, never()).insertActive(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(),
                any(ModelScene.class), any(ModelKind.class));
        verify(repository, never()).encryptApiKey(any());
        // 回退:各场景仍用 env 建内存模型(4 个场景)
        verify(provider, times(4)).init(any(ModelScene.class), any());
    }
}
