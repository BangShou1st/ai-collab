package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.common.ai.prompt.PromptCatalog;
import com.shitulelv.aicollab.infrastructure.ai.user.*;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/** 每轮模型请求准备时读取用户当前 AGENT 用途配置（用途分配优先，否则默认配置）。
 *  配置编辑/分配/默认变更在下一轮生效；agent_run 快照字段仅作诊断，不再锁定运行。
 *
 *  <p><b>J2 起同一份快照还承载 Prompt 身份</b>：{@code prompts} 键记录本次运行解析到的
 *  PromptPack 来源（BUNDLED/REMOTE）、manifest 指纹与 {@code key→version} 映射，
 *  使 {@code promptVersion} 可在 Run 中追溯（设计 §5.2 退出条件）。复用 V51 已存在的
 *  {@code model_configuration_snapshot} JSONB，不新增迁移；该键是<b>只增字段</b>，
 *  不改变原快照其它键的语义。</p> */
@Service
public class AgentModelConfigurationStore {
    private final JdbcTemplate jdbc;
    private final UserAiProviderService providers;
    private final PromptCatalog prompts;
    private final ObjectMapper json;
    public AgentModelConfigurationStore(JdbcTemplate jdbc, UserAiProviderService providers,
            PromptCatalog prompts, ObjectMapper json) {
        this.jdbc = jdbc; this.providers = providers; this.prompts = prompts; this.json = json;
    }
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
            // 快照记录本次请求解析出的模型身份与单次输出上限（诊断用）。
            // 注意：这是<b>单次</b>最大输出，不是运行的累计输出额度；两者不可混用。
            jdbc.update("UPDATE agent_run SET model_configuration_id=?,model_configuration_updated_at=?,model_configuration_snapshot=jsonb_build_object('configurationId',?::text,'updatedAt',?::text,'provider',?::text,'model',?::text,'maxOutputTokens',?::int,'budgetEnforced',?::boolean,'mode',?::text) WHERE id=?",
                    provider.id(),provider.updatedAt(),provider.id(),provider.updatedAt(),provider.providerType().name(),provider.modelName(),provider.maxOutputTokens(),true,
                    provider.capabilities().contains(com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability.NATIVE_TOOLS) ? "NATIVE_TOOLS" : "LEGACY_READ_ONLY",run.id());
        }
        recordPromptIdentity(run);
        return provider;
    }

    /**
     * 把当前 PromptPack 身份并入 Run 快照（同一条 JSONB，不新增列/迁移）。
     *
     * <p>幂等：只有 {@code prompts} 键真的变化时才写，因此每个运行在配置或 Prompt 版本
     * 变化后的第一轮落一次，而不是每轮都写。Prompt 目录尚无可用条目（远端未就绪且无内置包）
     * 时跳过，不写一个空映射冒充"已解析"。</p>
     */
    private void recordPromptIdentity(AgentRunView run) {
        Map<String, String> versions = prompts.versions();
        if (versions.isEmpty()) return;
        ObjectNode payload = json.createObjectNode()
                .put("source", prompts.source())
                .put("manifestFingerprint", prompts.manifestFingerprint());
        ObjectNode keys = payload.putObject("keys");
        versions.forEach(keys::put);
        String identity = payload.toString();
        jdbc.update("""
                UPDATE agent_run SET model_configuration_snapshot=
                  coalesce(model_configuration_snapshot,'{}'::jsonb) || jsonb_build_object('prompts',?::jsonb)
                WHERE project_id=? AND id=?
                  AND coalesce(model_configuration_snapshot->'prompts','{}'::jsonb) IS DISTINCT FROM ?::jsonb
                """, identity, run.projectId(), run.id(), identity);
    }
}
