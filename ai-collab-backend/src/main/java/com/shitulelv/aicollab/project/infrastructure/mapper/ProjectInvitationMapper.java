package com.shitulelv.aicollab.project.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.project.infrastructure.entity.ProjectInvitationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ProjectInvitationMapper extends BaseMapper<ProjectInvitationEntity> {

    @Select("SELECT * FROM project_invitation WHERE invite_code_hash = #{codeHash}")
    Optional<ProjectInvitationEntity> findByCodeHash(@Param("codeHash") String codeHash);

    @Select("SELECT * FROM project_invitation WHERE invite_code_hash = #{codeHash} FOR UPDATE")
    Optional<ProjectInvitationEntity> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    @Update("""
            UPDATE project_invitation
            SET status = 'ACCEPTED', accepted_by = #{userId}, accepted_at = #{acceptedAt}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markAccepted(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("acceptedAt") OffsetDateTime acceptedAt);
}
