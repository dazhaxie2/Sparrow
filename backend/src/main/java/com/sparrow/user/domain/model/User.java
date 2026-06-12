package com.sparrow.user.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("t_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("member_expire_at")
    private LocalDateTime memberExpireAt;

    @TableField(value = "created_at", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public LocalDateTime getMemberExpireAt() {
        return memberExpireAt;
    }

    public void setMemberExpireAt(LocalDateTime memberExpireAt) {
        this.memberExpireAt = memberExpireAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean memberActive() {
        return memberExpireAt != null && memberExpireAt.isAfter(LocalDateTime.now());
    }
}
