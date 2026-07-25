package com.shitulelv.aicollab.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Locale;
import java.util.UUID;

/**
 * 用户模块的查询与持久化门面。
 * 它位于 AuthService 与 UserMapper 之间，让 Controller 和认证业务不直接拼装数据库条件。
 */
@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public Optional<UserEntity> findByUsername(String username) {
        return Optional.ofNullable(userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username)));
    }

    public Optional<UserEntity> findById(UUID id) {
        return Optional.ofNullable(userMapper.selectById(id));
    }

    public Optional<UserEntity> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>()
                        .eq(UserEntity::getEmail, email.trim().toLowerCase(Locale.ROOT))));
    }

    /**
     * 只更新登录时间，避免为了一个字段把内存中的整份 Entity 写回数据库。
     */
    public void updateLastLoginAt(UUID userId, OffsetDateTime loginAt) {
        userMapper.update(null, new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .set(UserEntity::getLastLoginAt, loginAt));
    }

    public void create(UserEntity user) {
        userMapper.insert(user);
    }

    public boolean updateProfile(UUID userId, String displayName, String email) {
        return userMapper.update(null, new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .set(UserEntity::getDisplayName, displayName)
                .set(UserEntity::getEmail, email)
                .set(UserEntity::getUpdatedAt, OffsetDateTime.now())) == 1;
    }

    public boolean updatePassword(UUID userId, String passwordHash) {
        return userMapper.update(null, new LambdaUpdateWrapper<UserEntity>()
                .eq(UserEntity::getId, userId)
                .set(UserEntity::getPasswordHash, passwordHash)
                .setSql("token_version = token_version + 1")
                .set(UserEntity::getUpdatedAt, OffsetDateTime.now())) == 1;
    }
}
