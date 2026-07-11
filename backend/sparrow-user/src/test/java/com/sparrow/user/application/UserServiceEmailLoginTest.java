package com.sparrow.user.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sparrow.common.exception.BizException;
import com.sparrow.user.domain.model.User;
import com.sparrow.user.infrastructure.mail.MailSenderAdapter;
import com.sparrow.user.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        mailSender = mock(MailSenderAdapter.class);
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
        when(valueOps.get("sparrow:email-code:user@example.com")).thenReturn("123456");
        assertThatThrownBy(() -> service.loginByEmail("user@example.com", "000000"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码错误");
    }

    /** 邮箱不存在 → 自动注册并签发 token。 */
    @Test
    void loginByEmailAutoRegistersNewUser() {
        when(valueOps.get("sparrow:email-code:new@example.com")).thenReturn("999999");
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            setId((User) inv.getArgument(0), 42L);
            return 1;
        });

        String token = service.loginByEmail("new@example.com", "999999");

        assertThat(token).isNotBlank();
        verify(userMapper, times(1)).insert(any(User.class));
        // 验证码一次性使用:成功后删除
        verify(redis, times(1)).delete("sparrow:email-code:new@example.com");
    }

    /** 邮箱已存在 → 直接登录,不再注册。 */
    @Test
    void loginByEmailSkipsRegistrationForExistingUser() {
        when(valueOps.get("sparrow:email-code:old@example.com")).thenReturn("111111");
        User existing = new User();
        setId(existing, 7L);
        existing.setEmail("old@example.com");
        when(userMapper.selectOne(any())).thenReturn(existing);

        String token = service.loginByEmail("old@example.com", "111111");

        assertThat(token).isNotBlank();
        verify(userMapper, never()).insert(any(User.class));
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
                eq("9"), any());
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
        when(valueOps.get("sparrow:email-bind-code:admin@example.test")).thenReturn("246810");

        User updated = service.bindEmail(12L, "admin@example.test", "246810");

        assertThat(updated.getEmail()).isEqualTo("admin@example.test");
        assertThat(updated.effectiveRole()).isEqualTo("admin");
        verify(userMapper).updateById(existing);
        verify(redis).delete("sparrow:email-bind-code:admin@example.test");
    }

    @Test
    void bindEmailRejectsLoginPurposeCode() {
        when(valueOps.get("sparrow:email-bind-code:user@example.com")).thenReturn(null);

        assertThatThrownBy(() -> service.bindEmail(12L, "user@example.com", "123456"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验证码");

        verify(userMapper, never()).updateById(any(User.class));
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
