package com.shitulelv.aicollab.notification.infrastructure.repository;

import com.shitulelv.aicollab.notification.infrastructure.entity.AdminNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AdminNotificationRepository {
    private static final RowMapper<AdminNotification> MAPPER = (ResultSet rs, int row) -> {
        try {
            return new AdminNotification(
                    rs.getObject("id", UUID.class),
                    rs.getString("category"),
                    rs.getString("level"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getString("target_path"),
                    rs.getString("dedupe_key"),
                    rs.getBoolean("is_read"),
                    rs.getObject("read_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to map admin notification", exception);
        }
    };

    private static final String COLUMNS = """
            id, category, level, title, content, target_path, dedupe_key, is_read, read_at, created_at""";

    private final JdbcTemplate jdbc;

    public AdminNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(AdminNotification notification) {
        if (notification.dedupeKey() != null && !notification.dedupeKey().isBlank()) {
            // 检查最近 1 小时内是否有相同 dedupe_key 的未读通知
            Integer existing = jdbc.queryForObject(
                    "SELECT count(*) FROM admin_notification WHERE dedupe_key = ? AND is_read = false AND created_at > now() - interval '1 hour'",
                    Integer.class, notification.dedupeKey());
            if (existing != null && existing > 0) {
                // 已有未读相同事件，刷新时间，避免刷屏
                jdbc.update("UPDATE admin_notification SET created_at = CURRENT_TIMESTAMP WHERE dedupe_key = ? AND is_read = false",
                        notification.dedupeKey());
                return;
            }
        }

        jdbc.update("""
                INSERT INTO admin_notification (id, category, level, title, content, target_path, dedupe_key, is_read, read_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                notification.id(), notification.category(), notification.level(),
                notification.title(), notification.content(), notification.targetPath(),
                notification.dedupeKey(), notification.isRead(), notification.readAt(),
                notification.createdAt());
    }

    public List<AdminNotification> list(boolean unreadOnly, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM admin_notification "
                + (unreadOnly ? "WHERE is_read = false " : "")
                + "ORDER BY created_at DESC LIMIT " + Math.min(Math.max(limit, 1), 100);
        return jdbc.query(sql, MAPPER);
    }

    public int countUnread() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM admin_notification WHERE is_read = false", Integer.class);
        return count == null ? 0 : count;
    }

    public int markAsRead(UUID id) {
        return jdbc.update("UPDATE admin_notification SET is_read = true, read_at = CURRENT_TIMESTAMP WHERE id = ? AND is_read = false",
                id);
    }

    public int markAllAsRead() {
        return jdbc.update("UPDATE admin_notification SET is_read = true, read_at = CURRENT_TIMESTAMP WHERE is_read = false");
    }

    public Optional<AdminNotification> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM admin_notification WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }
}
