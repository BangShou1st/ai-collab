package com.shitulelv.aicollab.notification.infrastructure.repository;

import com.shitulelv.aicollab.notification.infrastructure.entity.NotificationEntity;
import com.shitulelv.aicollab.notification.infrastructure.mapper.NotificationMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final NotificationMapper mapper;

    public NotificationRepository(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    public void create(NotificationEntity entity) {
        mapper.insert(entity);
    }

    public List<NotificationEntity> listByUser(UUID userId, int limit, int offset) {
        return mapper.listByUser(userId, limit, offset);
    }

    public int countUnread(UUID userId) {
        return mapper.countUnread(userId);
    }

    public List<NotificationEntity> listByProject(UUID userId, UUID projectId, int limit, int offset) {
        return mapper.listByProject(userId, projectId, limit, offset);
    }

    public int markAsRead(UUID userId, UUID notificationId) {
        return mapper.markAsRead(userId, notificationId);
    }

    public int markAllAsRead(UUID userId) {
        return mapper.markAllAsRead(userId);
    }

    public Optional<NotificationEntity> findByEntity(UUID userId, String entityType, UUID entityId) {
        return mapper.findByEntity(userId, entityType, entityId);
    }
}
