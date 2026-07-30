package com.shitulelv.aicollab.project.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.project.application.view.ProjectView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.model.ProjectStatus;
import com.shitulelv.aicollab.project.domain.model.ProjectType;
import com.shitulelv.aicollab.project.infrastructure.entity.ProjectEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {

    @Select("""
            SELECT p.id, p.name, p.description, p.owner_id, p.type, p.start_date, p.due_date,
                   p.status, pm.role, p.version, p.created_at, p.updated_at
            FROM project p
            JOIN project_member pm ON pm.project_id = p.id
            WHERE pm.user_id = #{userId}
            ORDER BY p.created_at DESC
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "description", javaType = String.class),
            @Arg(column = "owner_id", javaType = UUID.class),
            @Arg(column = "type", javaType = ProjectType.class),
            @Arg(column = "start_date", javaType = LocalDate.class),
            @Arg(column = "due_date", javaType = LocalDate.class),
            @Arg(column = "status", javaType = ProjectStatus.class),
            @Arg(column = "role", javaType = ProjectRole.class),
            @Arg(column = "version", javaType = Integer.class),
            @Arg(column = "created_at", javaType = OffsetDateTime.class),
            @Arg(column = "updated_at", javaType = OffsetDateTime.class)
    })
    List<ProjectView> listForUser(@Param("userId") UUID userId);

    @Select("""
            SELECT p.id, p.name, p.description, p.owner_id, p.type, p.start_date, p.due_date,
                   p.status, pm.role, p.version, p.created_at, p.updated_at
            FROM project p
            JOIN project_member pm ON pm.project_id = p.id
            WHERE p.id = #{projectId} AND pm.project_id = #{projectId} AND pm.user_id = #{userId}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "description", javaType = String.class),
            @Arg(column = "owner_id", javaType = UUID.class),
            @Arg(column = "type", javaType = ProjectType.class),
            @Arg(column = "start_date", javaType = LocalDate.class),
            @Arg(column = "due_date", javaType = LocalDate.class),
            @Arg(column = "status", javaType = ProjectStatus.class),
            @Arg(column = "role", javaType = ProjectRole.class),
            @Arg(column = "version", javaType = Integer.class),
            @Arg(column = "created_at", javaType = OffsetDateTime.class),
            @Arg(column = "updated_at", javaType = OffsetDateTime.class)
    })
    Optional<ProjectView> findForMember(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    @Select("SELECT name FROM project WHERE id = #{projectId}")
    Optional<String> findNameById(@Param("projectId") UUID projectId);

    @Select("SELECT status FROM project WHERE id = #{projectId}")
    Optional<ProjectStatus> findStatusById(@Param("projectId") UUID projectId);

    @Select("SELECT id FROM project WHERE id = #{projectId} FOR UPDATE")
    Optional<UUID> lockById(@Param("projectId") UUID projectId);

    @Select("SELECT status FROM project WHERE id = #{projectId} FOR UPDATE")
    Optional<ProjectStatus> lockStatusById(@Param("projectId") UUID projectId);

    @Select("SELECT count(*) FROM project_document WHERE project_id = #{projectId}")
    int countDocuments(@Param("projectId") UUID projectId);

    @Update("""
            UPDATE project
            SET name = #{name}, description = #{description}, type = #{type},
                start_date = #{startDate}, due_date = #{dueDate}, status = #{status},
                version = version + 1, updated_at = now()
            WHERE id = #{projectId} AND version = #{version}
            """)
    int updateWithVersion(
            @Param("projectId") UUID projectId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("type") ProjectType type,
            @Param("startDate") LocalDate startDate,
            @Param("dueDate") LocalDate dueDate,
            @Param("status") ProjectStatus status,
            @Param("version") int version);

    @Update("""
            UPDATE project
            SET owner_id = #{newOwnerId}, updated_at = now()
            WHERE id = #{projectId}
            """)
    int transferOwnership(
            @Param("projectId") UUID projectId,
            @Param("newOwnerId") UUID newOwnerId);

}
