package com.sparrow.user;

import com.sparrow.common.AuthInterceptor;
import com.sparrow.common.BizException;
import com.sparrow.common.MembershipService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UserService implements MembershipService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final int tokenTtlDays;

    public UserService(UserRepository userRepository, StringRedisTemplate redis,
                       @Value("${sparrow.auth.token-ttl-days}") int tokenTtlDays) {
        this.userRepository = userRepository;
        this.redis = redis;
        this.tokenTtlDays = tokenTtlDays;
    }

    @Transactional
    public String register(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("用户名已被注册");
        }
        return issueToken(user.getId());
    }

    public String login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException("用户名或密码错误"));
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        return issueToken(user.getId());
    }

    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "用户不存在"));
    }

    @Override
    public boolean isMember(Long userId) {
        return userRepository.findById(userId).map(User::memberActive).orElse(false);
    }

    /** 支付成功后开通/续期会员(由 trade 模块在本地事务内调用) */
    @Transactional
    public void grantMembership(Long userId, int days) {
        User user = getById(userId);
        LocalDateTime base = user.memberActive() ? user.getMemberExpireAt() : LocalDateTime.now();
        user.setMemberExpireAt(base.plusDays(days));
        userRepository.save(user);
    }

    private String issueToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(AuthInterceptor.TOKEN_KEY_PREFIX + token,
                String.valueOf(userId), Duration.ofDays(tokenTtlDays));
        return token;
    }
}
