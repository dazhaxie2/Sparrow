package com.sparrow.user.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.TokenKeys;
import com.sparrow.user.domain.model.User;
import com.sparrow.user.infrastructure.mail.MailSenderAdapter;
import com.sparrow.user.infrastructure.persistence.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class UserService {

    private static final String EMAIL_LOGIN_CODE_KEY = "sparrow:email-code:";
    private static final String EMAIL_BIND_CODE_KEY = "sparrow:email-bind-code:";
    private static final String EMAIL_COOLDOWN_KEY = "sparrow:email-code:cooldown:";
    private static final String EMAIL_CODE_ATTEMPTS_KEY = "sparrow:email-code:attempts:";
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final DefaultRedisScript<Long> VERIFY_CODE_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then return 0 end
            if stored == ARGV[1] then
              redis.call('DEL', KEYS[1])
              redis.call('DEL', KEYS[2])
              return 1
            end
            local attempts = redis.call('INCR', KEYS[2])
            if attempts == 1 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3])) end
            if attempts >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
              redis.call('DEL', KEYS[2])
              return -1
            end
            return 0
            """, Long.class);

    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final MailSenderAdapter mailSender;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final int tokenTtlDays;
    private final int resendCooldownSeconds;
    private final String bootstrapAdminEmail;

    @Autowired
    public UserService(UserMapper userMapper, StringRedisTemplate redis, MailSenderAdapter mailSender,
                       @Value("${sparrow.auth.token-ttl-days:7}") int tokenTtlDays,
                       @Value("${sparrow.mail.resend-cooldown-seconds:60}") int resendCooldownSeconds,
                       @Value("${sparrow.auth.bootstrap-admin-email:}")
                       String bootstrapAdminEmail) {
        this.userMapper = userMapper;
        this.redis = redis;
        this.mailSender = mailSender;
        this.tokenTtlDays = tokenTtlDays;
        this.resendCooldownSeconds = resendCooldownSeconds;
        this.bootstrapAdminEmail = normalizeEmail(bootstrapAdminEmail);
    }

    /** Compatibility constructor for focused tests and non-Spring callers. */
    public UserService(UserMapper userMapper, StringRedisTemplate redis, MailSenderAdapter mailSender,
                       int tokenTtlDays, int resendCooldownSeconds) {
        this(userMapper, redis, mailSender, tokenTtlDays, resendCooldownSeconds, "");
    }

    @Transactional
    public String register(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException error) {
            throw new BizException("用户名已被注册");
        }
        return issueToken(user.getId());
    }

    /** Password login accepts either an exact username or a normalized bound email. */
    public String login(String identifier, String password) {
        if (identifier == null || identifier.isBlank() || password == null || password.isBlank()) {
            throw invalidCredentials();
        }
        String normalized = identifier.trim();
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        if (normalized.contains("@")) {
            query.eq(User::getEmail, normalizeEmail(normalized));
        } else {
            query.eq(User::getUsername, normalized);
        }
        User user = userMapper.selectOne(query);
        if (user == null || user.getPasswordHash() == null || user.getPasswordHash().isBlank()
                || !encoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return issueToken(user.getId());
    }

    /**
     * 已登录用户设置或修改自己的密码。邮箱验证码注册的新账号初始为空密码,
     * 用此方法补设后即可走密码登录。两次输入必须一致,长度 6-64(与注册一致)。
     */
    @Transactional
    public PasswordChange setPassword(long userId, String currentPassword,
                                      String password, String confirmPassword) {
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 64) {
            throw new BizException("密码长度需在 6 到 64 个字符之间");
        }
        if (!password.equals(confirmPassword)) {
            throw new BizException("两次输入的密码不一致");
        }
        User user = getById(userId);
        if (hasText(user.getPasswordHash())
                && (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash()))) {
            throw new BizException("当前密码不正确");
        }
        user.setPasswordHash(encoder.encode(password));
        userMapper.updateById(user);
        Long authVersion = redis.opsForValue().increment(TokenKeys.TOKEN_VERSION_PREFIX + userId);
        if (authVersion == null) {
            throw new IllegalStateException("无法更新用户认证版本");
        }
        redis.expire(TokenKeys.TOKEN_VERSION_PREFIX + userId, Duration.ofDays(tokenTtlDays));
        return new PasswordChange(user, issueToken(userId, authVersion));
    }

    public void sendEmailCode(String email) {
        sendCode(email, EMAIL_LOGIN_CODE_KEY, "login:");
    }

    /** Sends a purpose-scoped binding code after checking current account and ownership. */
    public void sendBindEmailCode(long userId, String email) {
        User user = getById(userId);
        if (hasText(user.getEmail())) {
            throw new BizException("当前账号已经绑定邮箱");
        }
        String normalized = requireValidEmail(email);
        User owner = findByEmail(normalized);
        if (owner != null && !owner.getId().equals(userId)) {
            throw new BizException("该邮箱已被其他账号绑定");
        }
        sendCode(normalized, EMAIL_BIND_CODE_KEY, "bind:");
    }

    private void sendCode(String email, String codeKeyPrefix, String cooldownPurpose) {
        String normalized = requireValidEmail(email);
        Boolean accepted = redis.opsForValue().setIfAbsent(
                EMAIL_COOLDOWN_KEY + cooldownPurpose + normalized,
                "1", Duration.ofSeconds(resendCooldownSeconds));
        if (Boolean.FALSE.equals(accepted)) {
            throw new BizException("发送过于频繁，请稍后再试");
        }
        String code = randomCode();
        redis.delete(EMAIL_CODE_ATTEMPTS_KEY + cooldownPurpose + normalized);
        redis.opsForValue().set(codeKeyPrefix + normalized, code,
                Duration.ofMinutes(mailSender.getCodeTtlMinutes()));
        mailSender.sendVerificationCode(normalized, code);
    }

    /** Verification-code login keeps the existing email-first registration behaviour. */
    public String loginByEmail(String email, String code) {
        String normalized = requireValidEmail(email);
        consumeCode(EMAIL_LOGIN_CODE_KEY, "login:", normalized, code);

        User user = findByEmail(normalized);
        if (user == null) {
            user = autoRegisterByEmail(normalized);
        } else {
            ensureBootstrapRole(user, normalized);
        }
        return issueToken(user.getId());
    }

    /** Binds an email to a legacy username account after a binding-purpose code succeeds. */
    @Transactional
    public User bindEmail(long userId, String email, String code) {
        String normalized = requireValidEmail(email);
        consumeCode(EMAIL_BIND_CODE_KEY, "bind:", normalized, code);

        User user = getById(userId);
        if (hasText(user.getEmail())) {
            if (normalized.equalsIgnoreCase(user.getEmail())) {
                return user;
            }
            throw new BizException("当前账号已经绑定邮箱");
        }
        User owner = findByEmail(normalized);
        if (owner != null && !owner.getId().equals(userId)) {
            throw new BizException("该邮箱已被其他账号绑定");
        }

        user.setEmail(normalized);
        if (isBootstrapAdmin(normalized)) {
            user.setRole("admin");
        }
        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException error) {
            throw new BizException("该邮箱已被其他账号绑定");
        }
        return user;
    }

    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user;
    }

    public boolean isMember(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.memberActive();
    }

    @Transactional
    public void grantMembership(Long userId, int days) {
        User user = getById(userId);
        LocalDateTime base = user.memberActive() ? user.getMemberExpireAt() : LocalDateTime.now();
        user.setMemberExpireAt(base.plusDays(days));
        userMapper.updateById(user);
    }

    private User autoRegisterByEmail(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9_-]", "");
        if (base.isBlank()) {
            base = "user";
        }
        for (int suffix = 0; suffix < 1000; suffix++) {
            User user = new User();
            user.setUsername(suffix == 0 ? base : base + suffix);
            user.setEmail(email);
            user.setPasswordHash("");
            if (isBootstrapAdmin(email)) {
                user.setRole("admin");
            }
            try {
                userMapper.insert(user);
                return user;
            } catch (DuplicateKeyException error) {
                User concurrentlyCreated = findByEmail(email);
                if (concurrentlyCreated != null) {
                    return concurrentlyCreated;
                }
                // The username, not the email, collided. Retry with the next suffix.
            }
        }
        throw new BizException(409, "无法分配唯一用户名，请稍后重试");
    }

    private void ensureBootstrapRole(User user, String email) {
        if (isBootstrapAdmin(email) && !"admin".equalsIgnoreCase(user.effectiveRole())) {
            user.setRole("admin");
            userMapper.updateById(user);
        }
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    private String issueToken(Long userId) {
        String storedVersion = redis.opsForValue().get(TokenKeys.TOKEN_VERSION_PREFIX + userId);
        long authVersion;
        try {
            authVersion = storedVersion == null ? 0L : Long.parseLong(storedVersion);
        } catch (NumberFormatException error) {
            throw new IllegalStateException("用户认证版本损坏", error);
        }
        return issueToken(userId, authVersion);
    }

    private String issueToken(Long userId, long authVersion) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(TokenKeys.TOKEN_KEY_PREFIX + token,
                userId + ":" + authVersion, Duration.ofDays(tokenTtlDays));
        return token;
    }

    private void consumeCode(String keyPrefix, String purpose, String email, String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("验证码不能为空");
        }
        Long result = redis.execute(VERIFY_CODE_SCRIPT,
                List.of(keyPrefix + email, EMAIL_CODE_ATTEMPTS_KEY + purpose + email),
                code, String.valueOf(MAX_CODE_ATTEMPTS),
                String.valueOf(Math.max(60, mailSender.getCodeTtlMinutes() * 60)));
        if (result != null && result == -1L) {
            throw new BizException("验证码错误次数过多，请重新获取");
        }
        if (result == null || result != 1L) {
            throw new BizException("验证码错误或已失效");
        }
    }

    private static BizException invalidCredentials() {
        return new BizException("用户名、邮箱或密码错误");
    }

    private static String randomCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private static String requireValidEmail(String email) {
        String normalized = normalizeEmail(email);
        if (!normalized.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new BizException("邮箱格式不正确");
        }
        return normalized;
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBootstrapAdmin(String email) {
        return !bootstrapAdminEmail.isBlank() && bootstrapAdminEmail.equals(normalizeEmail(email));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record PasswordChange(User user, String token) {
    }
}
