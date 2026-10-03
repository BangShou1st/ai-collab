package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.infrastructure.ai.user.*;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/** Pin config identity/version across worker ticks. Edits stop the run instead of silently switching. */
@Service
public class AgentModelConfigurationStore {
    private final JdbcTemplate jdbc;
    private final UserAiProviderService providers;
    public AgentModelConfigurationStore(JdbcTemplate jdbc, UserAiProviderService providers) { this.jdbc = jdbc; this.providers = providers; }
    @Transactional public UserAiProvider require(AgentRunView run) {
        com.shitulelv.aicollab.agent.infrastructure.repository.AgentLeaseScope.verify(jdbc,run.projectId(),run.id(),false);
        var rows = jdbc.queryForList("SELECT model_configuration_id,model_configuration_updated_at FROM agent_run WHERE id=? AND project_id=? FOR UPDATE", run.id(), run.projectId());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        Map<String,Object> row = rows.getFirst();
        UUID id = (UUID)row.get("model_configuration_id");
        UserAiProvider provider = id == null ? providers.resolve(run.requesterId(), ModelPurpose.AGENT)
                : providers.requireRuntimeConfiguration(run.requesterId(), id);
        if (!provider.enabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        if (id == null) jdbc.update("UPDATE agent_run SET model_configuration_id=?,model_configuration_updated_at=?,model_configuration_snapshot=jsonb_build_object('configurationId',?::text,'updatedAt',?::text,'provider',?::text,'model',?::text,'maxOutputTokens',?::int,'budgetEnforced',?::boolean,'mode',?::text) WHERE id=?",
                provider.id(),provider.updatedAt(),provider.id(),provider.updatedAt(),provider.providerType().name(),provider.modelName(),provider.maxOutputTokens(),!provider.isPreset(),
                provider.isPreset() || provider.capabilities().contains(com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.NATIVE_TOOLS) ? "NATIVE_TOOLS" : "LEGACY_READ_ONLY",run.id());
        else {
            OffsetDateTime pinned = jdbc.queryForObject("SELECT model_configuration_updated_at FROM agent_run WHERE id=?", OffsetDateTime.class, run.id());
            if (!pinned.toInstant().equals(provider.updatedAt().toInstant())) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "模型配置已变更，请重新发起运行");
        }
        return provider;
    }
}
