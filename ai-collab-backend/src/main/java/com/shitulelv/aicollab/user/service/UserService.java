package com.shitulelv.aicollab.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
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
}
