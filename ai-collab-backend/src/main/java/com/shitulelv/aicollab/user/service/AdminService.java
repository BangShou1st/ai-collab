package com.shitulelv.aicollab.user.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import com.shitulelv.aicollab.user.dto.AdminUserView;

/**
 * 系统管理员服务：管理账号，并在每个用例入口校验系统管理员身份。
 */
@Service
public class AdminService {
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public AdminService(
            UserService users,
            PasswordEncoder passwordEncoder,
            AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public List<AdminUserView> listUsers(UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        return users.findAll().stream().map(AdminUserView::from).toList();
    }

    /**
     * 创建测试账号。
     *
     * @param username    用户名
     * @param displayName 显示名称
     * @param password    密码
     * @param operatorId  操作者 ID
     * @return 创建的用户 ID
     */
    @Transactional
    public UUID createTestUser(String username, String displayName, String password, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        // 检查用户名是否已存在
        if (users.findByUsername(username).isPresent()) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName.trim());
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        users.create(user);

        audit.write(null, operatorId, "TEST_USER_CREATED", "USER", user.getId(),
                Map.of("username", username.trim()));

        return user.getId();
    }

    /**
     * 停用用户。
     *
     * @param userId     要停用的用户 ID
     * @param operatorId 操作者 ID
     */
    @Transactional
    public void disableUser(UUID userId, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        // 不能禁用自己
        if (userId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.CANNOT_DISABLE_SELF);
        }

        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (Boolean.TRUE.equals(user.getSystemAdmin())) {
            throw new BusinessException(ErrorCode.CANNOT_DISABLE_SUPER_ADMIN);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.USER_ALREADY_DISABLED);
        }

        users.updateStatus(userId, UserStatus.DISABLED);
        audit.write(null, operatorId, "USER_DISABLED", "USER", userId);
    }

    /**
     * 启用用户。
     *
     * @param userId     要启用的用户 ID
     * @param operatorId 操作者 ID
     */
    @Transactional
    public void enableUser(UUID userId, UUID operatorId) {
        users.requireSystemAdmin(operatorId);
        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_ALREADY_ACTIVE);
        }

        users.updateStatus(userId, UserStatus.ACTIVE);
        audit.write(null, operatorId, "USER_ENABLED", "USER", userId);
    }
}
