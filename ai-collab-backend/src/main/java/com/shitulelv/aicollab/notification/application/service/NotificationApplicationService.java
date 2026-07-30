package com.shitulelv.aicollab.notification.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.notification.application.view.NotificationView;
import com.shitulelv.aicollab.notification.infrastructure.entity.NotificationEntity;
import com.shitulelv.aicollab.notification.infrastructure.repository.NotificationRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationApplicationService {
    private final NotificationRepository repository;
    private final ProjectAccessGuard access;

    public NotificationApplicationService(NotificationRepository repository, ProjectAccessGuard access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(UUID userId, int page, int size) {
        int offset = pageOffset(page, size);
        return repository.listByUser(userId, size, offset).stream()
                .map(n -> NotificationView.from(n, n.getProjectName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public int countUnread(UUID userId) {
        return repository.countUnread(userId);
    }

    @Transactional(readOnly = true)
    public List<NotificationView> listByProject(UUID projectId, UUID userId, int page, int size) {
        access.requireMember(projectId, userId);
        int offset = pageOffset(page, size);
        return repository.listByProject(userId, projectId, size, offset).stream()
                .map(n -> NotificationView.from(n, n.getProjectName()))
                .toList();
    }

    @Transactional
    public void markAsRead(UUID userId, UUID notificationId) {
        int updated = repository.markAsRead(userId, notificationId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Transactional
    public void markAllAsRead(UUID userId) {
        repository.markAllAsRead(userId);
    }

    @Transactional
    public void create(UUID projectId, UUID userId, String type, String title,
                       String content, String entityType, UUID entityId) {
        NotificationEntity entity = new NotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setEntityType(entityType);
        entity.setEntityId(entityId);
        entity.setRead(false);
        entity.setCreatedAt(OffsetDateTime.now());
        repository.create(entity);
    }

    private static int pageOffset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page 不能小于 0，size 必须在 1 到 100 之间");
        }
        try {
            return Math.multiplyExact(page, size);
        } catch (ArithmeticException error) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "分页参数过大");
        }
    }
}
