package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.infrastructure.ai.user.*;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Pins a generation's PLANNING configuration across stages and process recovery, without credentials. */
@Service
public class PlanningModelConfigurationStore {
    private final JdbcTemplate jdbc;
    private final UserAiProviderService providers;
    public PlanningModelConfigurationStore(JdbcTemplate jdbc, UserAiProviderService providers) {
        this.jdbc=jdbc; this.providers=providers;
    }
    @Transactional
    public UserAiProvider require(UUID actor, UUID generation, Integer outputBudget) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,generation.toString());
        var rows=jdbc.queryForList("SELECT configuration_id,configuration_updated_at FROM planning_model_snapshot WHERE generation_id=? AND requester_id=?",generation,actor);
        var provider=rows.isEmpty() ? providers.resolve(actor,ModelPurpose.PLANNING)
                : providers.requireRuntimeConfiguration(actor,(UUID)rows.getFirst().get("configuration_id"));
        if(!provider.enabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        if(rows.isEmpty()) jdbc.update("INSERT INTO planning_model_snapshot(generation_id,requester_id,configuration_id,configuration_updated_at,snapshot) VALUES (?,?,?,?,jsonb_build_object('provider',?::text,'model',?::text,'maxOutputTokens',?::int,'budgetEnforced',?::boolean))",
                generation,actor,provider.id(),provider.updatedAt(),provider.providerType().name(),provider.modelName(),outputBudget==null?provider.maxOutputTokens():outputBudget,!"OPENCODE_ZEN_FREE".equals(provider.presetCode()));
        else if(!jdbc.queryForObject("SELECT configuration_updated_at FROM planning_model_snapshot WHERE generation_id=?",OffsetDateTime.class,generation).toInstant().equals(provider.updatedAt().toInstant()))
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,"规划模型配置已变更，请重新发起生成");
        return provider;
    }
}
