package com.shitulelv.aicollab.notification.application.service;

import com.shitulelv.aicollab.notification.infrastructure.entity.AdminNotification;
import com.shitulelv.aicollab.notification.infrastructure.repository.AdminNotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AdminNotificationService {
    private final AdminNotificationRepository repository;

    public AdminNotificationService(AdminNotificationRepository repository) {
        this.repository = repository;
    }

    public List<AdminNotification> list(boolean unreadOnly, int limit) {
        return repository.list(unreadOnly, limit);
    }

    public int countUnread() {
        return repository.countUnread();
    }

    @Transactional
    public void markAsRead(UUID id) {
        repository.markAsRead(id);
    }

    @Transactional
    public void markAllAsRead() {
        repository.markAllAsRead();
    }

    public void publishEvent(String category, String level, String title, String content, String targetPath, String dedupeKey) {
        repository.create(AdminNotification.fresh(category, level, title, content, targetPath, dedupeKey));
    }
}
