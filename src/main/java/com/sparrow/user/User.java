package com.sparrow.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username")
    private String username;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "member_expire_at")
    private LocalDateTime memberExpireAt;

    @Column(name = "created_at", insertable = false, updatable = false)
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
