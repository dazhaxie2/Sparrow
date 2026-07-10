package com.sparrow.industrychain.application;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.industrychain.application.ModelConfigService.SaveConfig;
import com.sparrow.industrychain.application.ModelConfigService.TestConfig;
import com.sparrow.industrychain.application.ModelConfigService.TestResult;
import com.sparrow.industrychain.infrastructure.client.UserClient;
import com.sparrow.industrychain.infrastructure.config.IndustryChainAiConfig;
import com.sparrow.industrychain.infrastructure.config.ModelConfig;
import com.sparrow.industrychain.infrastructure.llm.ChatModelProvider;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository;
import com.sparrow.industrychain.infrastructure.persistence.ModelConfigRepository.AuditRow;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置管理单测:聚焦权限校验、激活原子性、测试连接与脱敏,不依赖真实 LLM。
 */
class ModelConfigServiceTest {

    private ModelConfigRepository repository;
    private ChatModelProvider provider;
    private IndustryChainAiConfig aiConfig;
    private UserClient userClient;
    private ModelConfigService service;

    private static final long ADMIN_ID = 1L;
    private static final long USER_ID = 2L;

    @BeforeEach
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        provider = mock(ChatModelProvider.class);
        aiConfig = mock(IndustryChainAiConfig.class);
        userClient = mock(UserClient.class);
        service = new ModelConfigService(repository, provider, aiConfig, userClient);
    }

    private void asAdmin(long userId) {
        when(userClient.profile(userId)).thenReturn(
                ApiResponse.ok(Map.of("id", userId, "role", "admin")));
    }

    private void asRegular(long userId) {
        when(userClient.profile(userId)).thenReturn(
                ApiResponse.ok(Map.of("id", userId, "role", "user")));
    }

    // ── 权限 ──

    /** 非管理员调用激活被拒(403)。 */
    @Test
    void activateRejectsNonAdmin() {
        asRegular(USER_ID);
        assertThatThrownBy(() -> service.activate(10L, USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("管理员");
        verify(repository, never()).setActive(anyLong());
        verify(provider, never()).swap(any());
    }

    /** 管理员身份未知(Feign 返回 null)拒绝。 */
    @Test
    void activateRejectsUnknownUser() {
        when(userClient.profile(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> service.activate(10L, USER_ID))
                .isInstanceOf(BizException.class);
    }

    // ── 激活原子性 ──

    /** 配置不存在 → 抛 404,不切换。 */
    @Test
    void activateRejectsMissingConfig() {
        asAdmin(ADMIN_ID);
        when(repository.findDecryptedById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(99L, ADMIN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(provider, never()).swap(any());
    }

    /** 缺 API Key → 抛异常,不切换。 */
    @Test
    void activateRejectsBlankApiKey() {
        asAdmin(ADMIN_ID);
        when(repository.findDecryptedById(5L)).thenReturn(Optional.of(
                ModelConfig.decrypted(5L, "n", "http://u", "m", "", 3000, 180, 2, false)));
        assertThatThrownBy(() -> service.activate(5L, ADMIN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("API Key");
    }

    /** 正常激活:建模型成功 → setActive → 审计 → 提交后 swap。 */
    @Test
    void activateSwapsAndBroadcastsOnSuccess() {
        asAdmin(ADMIN_ID);
        ModelConfig cfg = ModelConfig.decrypted(5L, "glm", "http://u", "glm-4.5", "sk-key", 3000, 180, 2, false);
        when(repository.findDecryptedById(5L)).thenReturn(Optional.of(cfg));
        ChatModel built = mock(ChatModel.class);
        when(aiConfig.buildFrom(cfg)).thenReturn(built);

        service.activate(5L, ADMIN_ID);

        verify(repository, times(1)).setActive(5L);
        verify(provider, times(1)).swap(built);
        verify(repository, times(1)).audit(eq(5L), any(), eq(ADMIN_ID), eq("ACTIVATE"), any(), any());
    }

    /** 建模型失败 → 抛异常,不 setActive 不 swap(active 标记不动,保护当前可用模型)。 */
    @Test
    void activateFailsSafelyWhenBuildThrows() {
        asAdmin(ADMIN_ID);
        ModelConfig cfg = ModelConfig.decrypted(5L, "glm", "http://u", "glm-4.5", "sk-key", 3000, 180, 2, false);
        when(repository.findDecryptedById(5L)).thenReturn(Optional.of(cfg));
        when(aiConfig.buildFrom(cfg)).thenThrow(new RuntimeException("bad config"));

        assertThatThrownBy(() -> service.activate(5L, ADMIN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("构建模型失败");
        verify(repository, never()).setActive(anyLong());
        verify(provider, never()).swap(any());
    }

    // ── 测试连接 ──

    /** 测试连接缺 base_url / model_name 直接拒。 */
    @Test
    void testRejectsMissingFields() {
        asAdmin(ADMIN_ID);
        assertThatThrownBy(() -> service.test(
                new TestConfig("n", "", "m", "k", 30), ADMIN_ID))
                .isInstanceOf(BizException.class);
    }

    /** 测试连接无 key 且无激活配置可复用 → 拒。 */
    @Test
    void testRejectsWhenNoKeyAndNoActiveConfig() {
        asAdmin(ADMIN_ID);
        when(repository.findActiveDecrypted()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.test(
                new TestConfig("n", "http://u", "m", "", 30), ADMIN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("API Key");
    }

    // ── 保存 ──

    /** 未配置主密钥 → 保存被拒(防止明文落库)。 */
    @Test
    void saveRejectsWhenEncryptionNotReady() {
        asAdmin(ADMIN_ID);
        when(repository.encryptionReady()).thenReturn(false);
        assertThatThrownBy(() -> service.save(
                new SaveConfig(null, "n", "http://u", "m", "sk", 3000, 180, 2), ADMIN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主密钥");
    }

    /** 新增配置缺 API Key → 拒。 */
    @Test
    void saveRejectsNewConfigWithoutApiKey() {
        asAdmin(ADMIN_ID);
        when(repository.encryptionReady()).thenReturn(true);
        assertThatThrownBy(() -> service.save(
                new SaveConfig(null, "n", "http://u", "m", "", 3000, 180, 2), ADMIN_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("API Key");
    }

    /** 更新配置 apiKey 为空 → 保留旧 key(update 传 null)。 */
    @Test
    void saveKeepsOldKeyWhenApiKeyBlank() {
        asAdmin(ADMIN_ID);
        when(repository.encryptionReady()).thenReturn(true);
        when(repository.findDecryptedById(8L)).thenReturn(Optional.of(
                ModelConfig.decrypted(8L, "n", "http://u", "m", "old-key", 3000, 180, 2, false)));

        service.save(new SaveConfig(8L, "n2", "http://u2", "m2", "", 4000, 120, 1), ADMIN_ID);

        verify(repository, times(1)).update(eq(8L), eq("n2"), eq("http://u2"), eq("m2"),
                eq(null), eq(4000), eq(120), eq(1));
    }

    // ── 脱敏 ──

    /** ModelConfig.mask 只保留首尾,中间打码。 */
    @Test
    void maskNeverExposesFullKey() {
        assertThat(ModelConfig.mask("sk-1234567890abcdef")).isEqualTo("sk-1****cdef");
        assertThat(ModelConfig.mask("short")).isEqualTo("****");
        assertThat(ModelConfig.mask("")).isEmpty();
        assertThat(ModelConfig.mask(null)).isEmpty();
    }

    /** 列表返回脱敏视图(非明文)。 */
    @Test
    void listReturnsMaskedViews() {
        ModelConfig view = ModelConfig.forView(1L, "n", "u", "m", "sk-1****cdef",
                3000, 180, 2, true, null, null, null);
        when(repository.listAll()).thenReturn(List.of(view));
        List<ModelConfig> result = service.list();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).apiKeyMasked()).isEqualTo("sk-1****cdef");
        assertThat(result.get(0).apiKeyPlain()).isNull();
    }

    /** 审计 limit 边界裁剪。 */
    @Test
    void auditsClampsLimit() {
        when(repository.listAudits(50)).thenReturn(List.of());
        service.audits(0);     // <=0 → 50
        verify(repository).listAudits(50);
        service.audits(9999);  // >200 → 50
        verify(repository, times(2)).listAudits(50);
    }
}
