package com.sparrow.user.application;

import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.TokenKeys;
import com.sparrow.user.domain.model.User;
import com.sparrow.user.infrastructure.mail.MailSenderAdapter;
import com.sparrow.user.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮箱验证码登录单测:聚焦验证码校验、自动注册、冷却限流与角色字段。
 * (邮件实际投递由 JavaMailSender mock 接管,不发真实邮件。)
 */
class UserServiceEmailLoginTest {

    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private MailSenderAdapter mailSender;
    private UserService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        mailSender = mock(MailSenderAdapter.class);
        when(mailSender.getCodeTtlMinutes()).thenReturn(5);
        when(valueOps.increment(org.mockito.ArgumentMatchers.startsWith(TokenKeys.TOKEN_VERSION_PREFIX)))
                .thenReturn(1L);
        service = new UserService(userMapper, redis, mailSender, 7, 60);
    }

    // ── sendEmailCode ──

    /** 冷却窗口内重复请求被拒绝。 */
    @Test
    void sendEmailCodeRejectsDuringCooldown() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);
        assertThatThrownBy(() -> service.sendEmailCode("a@b.com"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("频繁");
        verify(mailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    /** 非法邮箱格式直接拒绝(不进入冷却)。 */
    @Test
    void sendEmailCodeRejectsInvalidFormat() {
        assertThatThrownBy(() -> service.sendEmailCode("not-an-email"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any());
    }

    /** 合法邮箱:写冷却键 + 存验证码(Redis),邮件异步发送。 */
    @Test
    void sendEmailCodeStoresCodeInRedis() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
        service.sendEmailCode("user@example.com");
        // 验证码与冷却键都写入 Redis
        verify(valueOps, atLeastOnce()).set(eq("sparrow:email-code:user@example.com"), anyString(), any());
    }

    // ── loginByEmail ──

    /** 验证码错误/失效拒绝登录。 */
    @Test
    void loginByEmailRejectsWrongCode() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(0L);
        assertThatThrownBy(() -> service.loginByEmail("user@example.com", "000000"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码错误");
    }

    /** 邮箱不存在 → 自动注册并签发 token。 */
    @Test
    void loginByEmailAutoRegistersNewUser() {
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            setId((User) inv.getArgument(0), 42L);
            return 1;
        });

        String token = service.loginByEmail("new@example.com", "999999");

        assertThat(token).isNotBlank();
        verify(userMapper, times(1)).insert(any(User.class));
        verify(redis).execute(any(RedisScript.class), anyList(), eq("999999"), eq("5"), eq("300"));
    }

    /** 邮箱已存在 → 直接登录,不再注册。 */
    @Test
    void loginByEmailSkipsRegistrationForExistingUser() {
        User existing = new User();
        setId(existing, 7L);
        existing.setEmail("old@example.com");
        when(userMapper.selectOne(any())).thenReturn(existing);

        String token = service.loginByEmail("old@example.com", "111111");

        assertThat(token).isNotBlank();
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void loginByEmailLocksCodeAfterTooManyFailures() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(-1L);

        assertThatThrownBy(() -> service.loginByEmail("user@example.com", "000000"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("次数过多");
    }

    @Test
    void autoRegistrationRetriesWhenUsernameCollides() {
        when(userMapper.insert(any(User.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("username collision"))
                .thenAnswer(inv -> {
                    setId((User) inv.getArgument(0), 43L);
                    return 1;
                });
        when(userMapper.selectOne(any())).thenReturn(null);

        String token = service.loginByEmail("same@example.com", "123456");

        assertThat(token).isNotBlank();
        verify(userMapper, times(2)).insert(any(User.class));
    }

    @Test
    void passwordLoginAcceptsRegisteredEmail() {
        User existing = new User();
        setId(existing, 9L);
        existing.setEmail("owner@example.com");
        existing.setPasswordHash(new BCryptPasswordEncoder().encode("secret12"));
        when(userMapper.selectOne(any())).thenReturn(existing);

        String token = service.login("OWNER@EXAMPLE.COM", "secret12");

        assertThat(token).isNotBlank();
        verify(valueOps).set(org.mockito.ArgumentMatchers.startsWith("sparrow:token:"),
                org.mockito.ArgumentMatchers.startsWith("9:"), any());
    }

    @Test
    void bindEmailUsesPurposeScopedCodeAndPromotesConfiguredAdmin() {
        service = new UserService(userMapper, redis, mailSender, 7, 60, "admin@example.test");
        User existing = new User();
        setId(existing, 12L);
        existing.setUsername("legacy-user");
        existing.setPasswordHash("hash");
        when(userMapper.selectById(12L)).thenReturn(existing);
        when(userMapper.selectOne(any())).thenReturn(null);

        User updated = service.bindEmail(12L, "admin@example.test", "246810");

        assertThat(updated.getEmail()).isEqualTo("admin@example.test");
        assertThat(updated.effectiveRole()).isEqualTo("admin");
        verify(userMapper).updateById(existing);
        verify(redis).execute(any(RedisScript.class), anyList(), eq("246810"), eq("5"), eq("300"));
    }

    @Test
    void bindEmailRejectsLoginPurposeCode() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.bindEmail(12L, "user@example.com", "123456"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码");

        verify(userMapper, never()).updateById(any(User.class));
    }

    // ── setPassword ──

    /** 两次密码一致且长度合法 → 设置成功,写入 passwordHash。 */
    @Test
    void setPasswordStoresHashWhenPasswordsMatch() {
        User existing = new User();
        setId(existing, 20L);
        existing.setEmail("nopass@example.com");
        existing.setPasswordHash("");
        when(userMapper.selectById(20L)).thenReturn(existing);

        UserService.PasswordChange changed = service.setPassword(20L, null, "secret12", "secret12");

        assertThat(changed.user().getPasswordHash()).isNotBlank();
        assertThat(changed.token()).isNotBlank();
        verify(valueOps).increment(TokenKeys.TOKEN_VERSION_PREFIX + "20");
        verify(redis).expire(eq(TokenKeys.TOKEN_VERSION_PREFIX + "20"), any());
        verify(userMapper).updateById(existing);
    }

    /** 两次密码不一致 → 拒绝,不写库。 */
    @Test
    void setPasswordRejectsWhenConfirmMismatch() {
        when(userMapper.selectById(20L)).thenReturn(new User());

        assertThatThrownBy(() -> service.setPassword(20L, null, "secret12", "different1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不一致");

        verify(userMapper, never()).updateById(any(User.class));
    }

    /** 密码过短 → 拒绝。 */
    @Test
    void setPasswordRejectsTooShortPassword() {
        assertThatThrownBy(() -> service.setPassword(20L, null, "abc", "abc"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("长度");

        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changingExistingPasswordRequiresCurrentPassword() {
        User existing = new User();
        setId(existing, 20L);
        existing.setPasswordHash(new BCryptPasswordEncoder().encode("old-secret"));
        when(userMapper.selectById(20L)).thenReturn(existing);

        assertThatThrownBy(() -> service.setPassword(20L, "wrong", "new-secret", "new-secret"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("当前密码");

        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changingExistingPasswordRevokesOlderTokensAndIssuesReplacement() {
        User existing = new User();
        setId(existing, 20L);
        existing.setPasswordHash(new BCryptPasswordEncoder().encode("old-secret"));
        when(userMapper.selectById(20L)).thenReturn(existing);

        UserService.PasswordChange changed = service.setPassword(
                20L, "old-secret", "new-secret", "new-secret");

        assertThat(new BCryptPasswordEncoder().matches("new-secret", changed.user().getPasswordHash())).isTrue();
        verify(valueOps).increment(TokenKeys.TOKEN_VERSION_PREFIX + "20");
        verify(valueOps).set(org.mockito.ArgumentMatchers.startsWith(TokenKeys.TOKEN_KEY_PREFIX),
                org.mockito.ArgumentMatchers.startsWith("20:"), any());
    }

    /** User 实体 id 由 MyBatis 自增,无 setter;测试用反射写入。 */
    private static void setId(User user, long id) {
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** User 实体 role 字段读写 + effectiveRole 兼容老数据。 */
    @Test
    void userEntityRoleDefaultsToUserWhenNull() {
        User u = new User();
        assertThat(u.effectiveRole()).isEqualTo("user");
        u.setRole("admin");
        assertThat(u.effectiveRole()).isEqualTo("admin");
    }
}
