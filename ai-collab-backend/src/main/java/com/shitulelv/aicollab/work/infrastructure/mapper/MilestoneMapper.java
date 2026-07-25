package com.shitulelv.aicollab.work.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.work.infrastructure.entity.MilestoneEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface MilestoneMapper extends BaseMapper<MilestoneEntity> {
    @Select("""
            SELECT * FROM milestone
            WHERE project_id=#{projectId}
            ORDER BY sort_order, target_date NULLS LAST, created_at
            """)
    List<MilestoneEntity> listByProject(@Param("projectId") UUID projectId);

    @Select("SELECT * FROM milestone WHERE project_id=#{projectId} AND id=#{id}")
    Optional<MilestoneEntity> findScoped(@Param("projectId") UUID projectId, @Param("id") UUID id);

    @Update("""
            UPDATE milestone SET name=#{item.name}, description=#{item.description},
              target_date=#{item.targetDate}, status=#{item.status}, sort_order=#{item.sortOrder},
              version=version+1, updated_at=now()
            WHERE project_id=#{projectId} AND id=#{item.id} AND version=#{item.version}
            """)
    int updateScoped(@Param("projectId") UUID projectId, @Param("item") MilestoneEntity item);

    @Delete("DELETE FROM milestone WHERE project_id=#{projectId} AND id=#{id}")
    int deleteScoped(@Param("projectId") UUID projectId, @Param("id") UUID id);
}
