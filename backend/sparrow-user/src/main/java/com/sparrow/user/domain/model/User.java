package com.sparrow.user.domain.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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

    private String email;

    /** 角色：user(默认) / admin。决定能否访问管理端。 */
    private String role;

    @TableField("member_expire_at")
    private LocalDateTime memberExpireAt;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    /** role 兼容老数据(NULL 视作普通用户)。 */
    public String effectiveRole() {
        return role == null || role.isBlank() ? "user" : role;
    }
}
