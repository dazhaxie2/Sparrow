package com.sparrow.user.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sparrow.common.exception.BizException;
import com.sparrow.common.security.TokenKeys;
import com.sparrow.user.domain.model.User;
import com.sparrow.user.infrastructure.persistence.UserMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Redis 验证码 key 前缀。 */
    private static final String EMAIL_CODE_KEY = "sparrow:email-code:";
    /** Redis 重发冷却 key 前缀。 */
    private static final String EMAIL_COOLDOWN_KEY = "sparrow:email-code:cooldown:";

    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final JavaMailSender mailSender;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final int tokenTtlDays;
    private final int codeTtlMinutes;
    private final int resendCooldownSeconds;
    private final String fromPersonal;

    @Autowired
    public UserService(UserMapper userMapper, StringRedisTemplate redis, JavaMailSender mailSender,
                       @Value("${sparrow.auth.token-ttl-days:7}") int tokenTtlDays,
                       @Value("${sparrow.mail.code-ttl-minutes:5}") int codeTtlMinutes,
                       @Value("${sparrow.mail.resend-cooldown-seconds:60}") int resendCooldownSeconds,
                       @Value("${sparrow.mail.from-personal:Sparrow 科技图}") String fromPersonal) {
        this.userMapper = userMapper;
        this.redis = redis;
        this.mailSender = mailSender;
        this.tokenTtlDays = tokenTtlDays;
        this.codeTtlMinutes = codeTtlMinutes;
        this.resendCooldownSeconds = resendCooldownSeconds;
        this.fromPersonal = fromPersonal;
    }

    @Transactional
    public String register(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException("用户名已被注册");
        }
        return issueToken(user.getId());
    }

    public String login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BizException("用户名或密码错误");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        return issueToken(user.getId());
    }

    /**
     * 发送邮箱验证码。
     * <p>同一邮箱 {@link #resendCooldownSeconds} 秒内不可重发(Redis 冷却键),验证码 TTL {@link #codeTtlMinutes} 分钟。
     * 邮件发送异步执行,不阻塞请求。
     */
    public void sendEmailCode(String email) {
        if (email == null || !email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            throw new BizException("邮箱格式不正确");
        }
        String lowerEmail = email.toLowerCase();
        // 冷却限流:冷却键存在即拒绝
        Boolean set = redis.opsForValue().setIfAbsent(EMAIL_COOLDOWN_KEY + lowerEmail, "1",
                Duration.ofSeconds(resendCooldownSeconds));
        if (Boolean.FALSE.equals(set)) {
            throw new BizException("发送过于频繁,请稍后再试");
        }
        String code = randomCode();
        redis.opsForValue().set(EMAIL_CODE_KEY + lowerEmail, code, Duration.ofMinutes(codeTtlMinutes));
        sendCodeMailAsync(lowerEmail, code);
    }

    /**
     * 邮箱验证码登录:校验验证码,邮箱未注册时自动注册(无密码),否则直接签发 token。
     */
    public String loginByEmail(String email, String code) {
        if (email == null || code == null) {
            throw new BizException("邮箱或验证码不能为空");
        }
        String lowerEmail = email.toLowerCase();
        String stored = redis.opsForValue().get(EMAIL_CODE_KEY + lowerEmail);
        if (stored == null || !stored.equals(code)) {
            throw new BizException("验证码错误或已失效");
        }
        // 一次性使用
        redis.delete(EMAIL_CODE_KEY + lowerEmail);

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, lowerEmail));
        if (user == null) {
            user = autoRegisterByEmail(lowerEmail);
        }
        return issueToken(user.getId());
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

    /** 邮箱自动注册:username 取邮箱前缀,重复则加数字后缀;无密码。 */
    private User autoRegisterByEmail(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9_-]", "");
        if (base.isBlank()) {
            base = "user";
        }
        String username = base;
        int suffix = 0;
        while (existsByUsername(username)) {
            suffix++;
            username = base + suffix;
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(""); // 邮箱用户无密码,只能用验证码登录
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发同邮箱注册,直接查回
            return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        }
        return user;
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

    /** 仅更新 role(管理端用)。 */
    public void updateRole(Long userId, String role) {
        int rows = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId).set(User::getRole, role));
        if (rows == 0) {
            throw new BizException(404, "用户不存在");
        }
    }

    private static String randomCode() {
        return String.format("%06d", new Random().nextInt(1_000_000));
    }

    @Async
    void sendCodeMailAsync(String to, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(new InternetAddress("noreply@sparrow.tech", fromPersonal));
            helper.setTo(to);
            helper.setSubject("【Sparrow 科技图】登录验证码");
            helper.setText("您正在登录 Sparrow 科技图。验证码：<b>" + code + "</b>，"
                    + codeTtlMinutes + " 分钟内有效。如非本人操作请忽略本邮件。", true);
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("验证码邮件构建失败 to={}: {}", to, e.getMessage());
            throw new BizException("验证码邮件发送失败");
        } catch (Exception e) {
            // SMTP 未配置/凭证错误等:不把堆栈冒泡成 500,给出可读提示
            log.error("验证码邮件发送失败 to={}: {}", to, e.getMessage());
            throw new BizException("验证码邮件发送失败,请稍后重试或联系管理员");
        }
    }
}
