package com.shitulelv.aicollab.project.infrastructure.mapper;

import com.shitulelv.aicollab.project.application.view.MemberView;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ProjectMemberMapper {

    @Insert("""
            INSERT INTO project_member(project_id, user_id, role, invited_by)
            VALUES (#{projectId}, #{userId}, #{role}, #{invitedBy})
            """)
    int insertMember(
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId,
            @Param("role") ProjectRole role,
            @Param("invitedBy") UUID invitedBy);

    @Insert("""
            INSERT INTO project_member(project_id, user_id, role, invited_by)
            VALUES (#{projectId}, #{userId}, #{role}, #{invitedBy})
            ON CONFLICT (project_id, user_id) DO NOTHING
            """)
    int insertMemberIfAbsent(
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId,
            @Param("role") ProjectRole role,
            @Param("invitedBy") UUID invitedBy);

    @Select("""
            SELECT role FROM project_member
            WHERE project_id = #{projectId} AND user_id = #{userId}
            """)
    Optional<ProjectRole> findRole(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    @Select("""
            SELECT u.id AS user_id, u.username, u.display_name, pm.role, pm.joined_at
            FROM project_member pm
            JOIN app_user u ON u.id = pm.user_id
            WHERE pm.project_id = #{projectId}
            ORDER BY CASE pm.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, pm.joined_at
            """)
    @ConstructorArgs({
            @Arg(column = "user_id", javaType = UUID.class),
            @Arg(column = "username", javaType = String.class),
            @Arg(column = "display_name", javaType = String.class),
            @Arg(column = "role", javaType = ProjectRole.class),
            @Arg(column = "joined_at", javaType = OffsetDateTime.class)
    })
    List<MemberView> listMembers(@Param("projectId") UUID projectId);

    @Update("""
            UPDATE project_member SET role = #{role}
            WHERE project_id = #{projectId} AND user_id = #{userId} AND role <> 'OWNER'
            """)
    int updateNonOwnerRole(
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId,
            @Param("role") ProjectRole role);

    @Delete("""
            DELETE FROM project_member
            WHERE project_id = #{projectId} AND user_id = #{userId} AND role <> 'OWNER'
            """)
    int deleteNonOwner(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    @Select("""
            SELECT u.id AS user_id, u.username, u.display_name, pm.role, pm.joined_at
            FROM project_member pm
            JOIN app_user u ON u.id = pm.user_id
            WHERE pm.project_id = #{projectId} AND pm.user_id = #{userId}
            """)
    @ConstructorArgs({
            @Arg(column = "user_id", javaType = UUID.class),
            @Arg(column = "username", javaType = String.class),
            @Arg(column = "display_name", javaType = String.class),
            @Arg(column = "role", javaType = ProjectRole.class),
            @Arg(column = "joined_at", javaType = OffsetDateTime.class)
    })
    Optional<MemberView> findMember(@Param("projectId") UUID projectId, @Param("userId") UUID userId);
}
