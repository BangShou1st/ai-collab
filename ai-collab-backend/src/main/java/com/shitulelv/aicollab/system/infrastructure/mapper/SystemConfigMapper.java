package com.shitulelv.aicollab.system.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.system.infrastructure.entity.SystemConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfigEntity> {

    @Update("""
        UPDATE system_configuration
        SET agent_enabled = #{entity.agentEnabled},
            chat_enabled = #{entity.chatEnabled},
            worker_concurrency = #{entity.workerConcurrency},
            worker_delay_ms = #{entity.workerDelayMs},
            schedule_delay_ms = #{entity.scheduleDelayMs},
            system_maintenance_mode = #{entity.systemMaintenanceMode},
            system_maintenance_message = #{entity.systemMaintenanceMessage},
            public_registration_enabled = #{entity.publicRegistrationEnabled},
            default_chat_model = #{entity.defaultChatModel},
            default_agent_model = #{entity.defaultAgentModel},
            rag_retrieval_threshold = #{entity.ragRetrievalThreshold},
            rag_max_sources = #{entity.ragMaxSources},
            version = #{entity.version},
            updated_by = #{entity.updatedBy},
            updated_at = #{entity.updatedAt}
        WHERE id = #{entity.id} AND version = #{expectedVersion}
    """)
    int updateWithOptimisticLock(@Param("entity") SystemConfigEntity entity, @Param("expectedVersion") long expectedVersion);
}
