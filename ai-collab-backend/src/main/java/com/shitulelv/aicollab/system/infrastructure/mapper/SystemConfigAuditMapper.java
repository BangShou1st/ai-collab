package com.shitulelv.aicollab.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.system.infrastructure.entity.SystemConfigAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SystemConfigAuditMapper extends BaseMapper<SystemConfigAuditEntity> {

    @Insert("""
        INSERT INTO system_config_audit (
            id, config_version, action, changed_by, changed_by_name,
            change_summary, config_snapshot, applied_status, error_message, created_at
        ) VALUES (
            #{id}, #{configVersion}, #{action}, #{changedBy}, #{changedByName},
            #{changeSummary}, #{configSnapshot}::jsonb, #{appliedStatus}, #{errorMessage}, #{createdAt}
        )
    """)
    int insertWithJsonb(SystemConfigAuditEntity entity);

    @Select("""
        SELECT id, config_version, action, changed_by, changed_by_name,
               change_summary, config_snapshot, applied_status, error_message, created_at
        FROM system_config_audit
        ORDER BY created_at DESC
        LIMIT #{limit}
    """)
    List<SystemConfigAuditEntity> selectRecent(@Param("limit") int limit);

    @Select("""
        SELECT id, config_version, action, changed_by, changed_by_name,
               change_summary, config_snapshot, applied_status, error_message, created_at
        FROM system_config_audit
        WHERE id = #{id}
    """)
    SystemConfigAuditEntity selectByIdCustom(@Param("id") java.util.UUID id);
}
