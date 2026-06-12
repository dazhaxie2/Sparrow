package com.sparrow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sparrow.entity.po.User;
import com.sparrow.mapper.UserMapper;
import com.sparrow.interceptor.AuthInterceptor;
import com.sparrow.exception.BizException;
import com.sparrow.service.MembershipGrantService;
import com.sparrow.service.MembershipService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UserService implements MembershipService, MembershipGrantService {

    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final int tokenTtlDays;

    public UserService(UserMapper userMapper, StringRedisTemplate redis,
                       @Value("${sparrow.auth.token-ttl-days}") int tokenTtlDays) {
        this.userMapper = userMapper;
        this.redis = redis;
        this.tokenTtlDays = tokenTtlDays;
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

    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user;
    }

    @Override
    public boolean isMember(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && user.memberActive();
    }

    @Override
    @Transactional
    public void grantMembership(Long userId, int days) {
        User user = getById(userId);
        LocalDateTime base = user.memberActive() ? user.getMemberExpireAt() : LocalDateTime.now();
        user.setMemberExpireAt(base.plusDays(days));
        userMapper.updateById(user);
    }

    private String issueToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(AuthInterceptor.TOKEN_KEY_PREFIX + token,
                String.valueOf(userId), Duration.ofDays(tokenTtlDays));
        return token;
    }
}
