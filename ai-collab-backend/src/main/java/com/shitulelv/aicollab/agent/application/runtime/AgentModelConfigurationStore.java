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

/** 每轮模型请求准备时读取用户当前 AGENT 用途配置（用途分配优先，否则默认配置）。
 *  配置编辑/分配/默认变更在下一轮生效；agent_run 快照字段仅作诊断，不再锁定运行。 */
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
        UUID previous = (UUID)row.get("model_configuration_id");
        // 每轮重新解析：不能沿用旧配置身份；用户当前不可用配置沿现有错误分类反馈，不自动换其他模型
        UserAiProvider provider = providers.resolve(run.requesterId(), ModelPurpose.AGENT);
        if (!provider.enabled()) throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        boolean unchanged = provider.id().equals(previous);
        if (unchanged) {
            OffsetDateTime pinnedAt = jdbc.queryForObject("SELECT model_configuration_updated_at FROM agent_run WHERE id=?", OffsetDateTime.class, run.id());
            unchanged = pinnedAt != null && pinnedAt.toInstant().equals(provider.updatedAt().toInstant());
        }
        if (!unchanged) {
            jdbc.update("UPDATE agent_run SET model_configuration_id=?,model_configuration_updated_at=?,model_configuration_snapshot=jsonb_build_object('configurationId',?::text,'updatedAt',?::text,'provider',?::text,'model',?::text,'maxOutputTokens',?::int,'budgetEnforced',?::boolean,'mode',?::text) WHERE id=?",
                    provider.id(),provider.updatedAt(),provider.id(),provider.updatedAt(),provider.providerType().name(),provider.modelName(),provider.maxOutputTokens(),!provider.isPreset(),
                    provider.isPreset() || provider.capabilities().contains(com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.NATIVE_TOOLS) ? "NATIVE_TOOLS" : "LEGACY_READ_ONLY",run.id());
        }
        return provider;
    }
}
