package com.shitulelv.aicollab.notification.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.notification.infrastructure.entity.NotificationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {
    String SELECT_VIEW = """
            SELECT n.*, p.name AS project_name
            FROM notification n
            JOIN project p ON p.id = n.project_id
            """;

    @Select(SELECT_VIEW + """
            WHERE n.user_id = #{userId}
            ORDER BY n.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<NotificationEntity> listByUser(
            @Param("userId") UUID userId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM notification WHERE user_id = #{userId} AND is_read = false")
    int countUnread(@Param("userId") UUID userId);

    @Select(SELECT_VIEW + """
            WHERE n.user_id = #{userId} AND n.project_id = #{projectId}
            ORDER BY n.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<NotificationEntity> listByProject(
            @Param("userId") UUID userId,
            @Param("projectId") UUID projectId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Update("UPDATE notification SET is_read = true WHERE user_id = #{userId} AND id = #{notificationId}")
    int markAsRead(@Param("userId") UUID userId, @Param("notificationId") UUID notificationId);

    @Update("UPDATE notification SET is_read = true WHERE user_id = #{userId} AND is_read = false")
    int markAllAsRead(@Param("userId") UUID userId);

    @Select("""
            SELECT n.* FROM notification n
            WHERE n.user_id = #{userId} AND n.entity_type = #{entityType} AND n.entity_id = #{entityId}
            ORDER BY n.created_at DESC LIMIT 1
            """)
    Optional<NotificationEntity> findByEntity(
            @Param("userId") UUID userId,
            @Param("entityType") String entityType,
            @Param("entityId") UUID entityId);
}
