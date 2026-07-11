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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class UserService {

    private static final String EMAIL_LOGIN_CODE_KEY = "sparrow:email-code:";
    private static final String EMAIL_BIND_CODE_KEY = "sparrow:email-bind-code:";
    private static final String EMAIL_COOLDOWN_KEY = "sparrow:email-code:cooldown:";

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
        redis.opsForValue().set(codeKeyPrefix + normalized, code,
                Duration.ofMinutes(mailSender.getCodeTtlMinutes()));
        mailSender.sendVerificationCode(normalized, code);
    }

    /** Verification-code login keeps the existing email-first registration behaviour. */
    public String loginByEmail(String email, String code) {
        String normalized = requireValidEmail(email);
        requireCode(EMAIL_LOGIN_CODE_KEY, normalized, code);
        redis.delete(EMAIL_LOGIN_CODE_KEY + normalized);

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
        requireCode(EMAIL_BIND_CODE_KEY, normalized, code);

        User user = getById(userId);
        if (hasText(user.getEmail())) {
            if (normalized.equalsIgnoreCase(user.getEmail())) {
                redis.delete(EMAIL_BIND_CODE_KEY + normalized);
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
        redis.delete(EMAIL_BIND_CODE_KEY + normalized);
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
        String username = base;
        int suffix = 0;
        while (existsByUsername(username)) {
            username = base + (++suffix);
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash("");
        if (isBootstrapAdmin(email)) {
            user.setRole("admin");
        }
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException error) {
            return findByEmail(email);
        }
        return user;
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

    private boolean existsByUsername(String username) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return count != null && count > 0;
    }

    private String issueToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(TokenKeys.TOKEN_KEY_PREFIX + token,
                String.valueOf(userId), Duration.ofDays(tokenTtlDays));
        return token;
    }

    private void requireCode(String keyPrefix, String email, String code) {
        if (code == null || code.isBlank()) {
            throw new BizException("验证码不能为空");
        }
        String stored = redis.opsForValue().get(keyPrefix + email);
        if (stored == null || !stored.equals(code)) {
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
}
