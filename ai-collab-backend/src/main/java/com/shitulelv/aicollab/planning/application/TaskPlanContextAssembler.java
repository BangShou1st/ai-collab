package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.retrieval.EvidenceRef;
import com.shitulelv.aicollab.common.retrieval.SeedEvidenceInput;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import com.shitulelv.aicollab.planning.domain.PlanningPromptText;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import com.shitulelv.aicollab.retrieval.application.EvidenceVerificationService;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class TaskPlanContextAssembler {
    /** 对齐 {@code TaskPlanDraftValidator} 的 {@code S1..S12} 来源命名空间上限。 */
    public static final int MAX_PLANNING_SOURCES = 12;

    private final JdbcTemplate jdbc;
    private final ProjectKnowledgeRetrieval retrieval;
    private final ObjectMapper json;
    private final PlanningModelProperties properties;

    @Autowired(required = false)
    private SystemConfigService systemConfigService;

    @Autowired(required = false)
    private EvidenceVerificationService evidenceVerificationService;

    public TaskPlanContextAssembler(JdbcTemplate jdbc, ProjectKnowledgeRetrieval retrieval,
                                    ObjectMapper json, PlanningModelProperties properties) {
        this.jdbc = jdbc; this.retrieval = retrieval; this.json = json; this.properties = properties;
    }

    public PlanningContext assemble(TaskPlanRecord plan) {
        List<UUID> documentIds;
        try { documentIds = json.readValue(plan.selectedDocumentIdsJson(), new TypeReference<>() {}); }
        catch (Exception invalidStoredInput) { throw new IllegalStateException("Invalid selected documents", invalidStoredInput); }
        List<SeedEvidenceInput> seedInputs = parseSeedInputs(plan.seedEvidenceJson());
        var project = jdbc.queryForMap("SELECT name,description,start_date,due_date FROM project WHERE id=?", plan.projectId());
        var members = jdbc.queryForList("""
                SELECT u.id,u.display_name,pm.role FROM project_member pm JOIN app_user u ON u.id=pm.user_id
                WHERE pm.project_id=? ORDER BY u.id
                """, plan.projectId());
        var milestones = jdbc.queryForList("SELECT name,target_date,status FROM milestone WHERE project_id=? ORDER BY sort_order", plan.projectId());
        var tasks = jdbc.queryForList("""
                SELECT title,status,due_date FROM project_task WHERE project_id=? AND status NOT IN ('DONE','CANCELED')
                ORDER BY created_at
                """, plan.projectId());
        int maxSources = effectiveMaxSources();
        double retrievalThreshold = systemConfigService != null
                ? systemConfigService.getRagRetrievalThreshold()
                : properties.retrievalThreshold();

        List<PlanSource> sources = new ArrayList<>();
        HashSet<String> hashes = new HashSet<>();
        HashSet<UUID> seenChunkIds = new HashSet<>();
        int remaining = properties.sourceCodepointBudget();

        // 1. 优先复用 Agent 等上游交接并经实时权限/状态/哈希重验的种子证据（注册为 S1..Sk）
        List<EvidenceRef> verifiedSeeds = (seedInputs.isEmpty() || evidenceVerificationService == null)
                ? List.of()
                : evidenceVerificationService.verifySeedEvidence(
                        plan.projectId(), plan.createdBy(), seedInputs, maxSources);
        for (EvidenceRef ref : verifiedSeeds) {
            if (sources.size() >= maxSources || ref.excerpt() == null
                    || seenChunkIds.contains(ref.chunkId())
                    || hashes.contains(ref.contentHash())) {
                continue;
            }
            int length = ref.excerpt().codePointCount(0, ref.excerpt().length());
            if (length > remaining) break;
            seenChunkIds.add(ref.chunkId());
            hashes.add(ref.contentHash());
            sources.add(new PlanSource("S" + (sources.size() + 1), ref.documentId(),
                    ref.filename(), ref.chunkId(), ref.heading(), ref.similarity(),
                    ref.excerpt(), ref.contentHash()));
            remaining -= length;
        }

        // 2. 若名额未满且存在指定文档，走统一 PLANNING_SOURCE 意图检索补充 S(k+1)..Sn
        // 权限收紧：以 plan.createdBy() 作为检索人强制项目成员校验；非成员时失败关闭。
        if (sources.size() < maxSources && remaining > 0 && !documentIds.isEmpty()) {
            var hits = retrieval.retrieve(new ProjectKnowledgeRetrieval.IntentRequest(
                    plan.projectId(), plan.createdBy(),
                    plan.title() + "\n" + plan.goal() + "\n" + plan.constraints(),
                    documentIds, Math.min(20, maxSources),
                    ProjectKnowledgeRetrieval.RetrievalIntent.PLANNING_SOURCE)).hits();
            for (var hit : hits) {
                if (sources.size() >= maxSources || hit.similarity() < retrievalThreshold
                        || hit.content() == null
                        || (hit.id() != null && seenChunkIds.contains(hit.id()))
                        || hashes.contains(hit.contentHash())) continue;
                int length = hit.content().codePointCount(0, hit.content().length());
                if (length > remaining) break;
                if (hit.id() != null) {
                    seenChunkIds.add(hit.id());
                }
                hashes.add(hit.contentHash());
                sources.add(new PlanSource("S" + (sources.size() + 1), hit.documentId(),
                        hit.originalFilename(), hit.id(), hit.heading(), hit.similarity(),
                        hit.content(), hit.contentHash()));
                remaining -= length;
            }
        }
        String prompt = "<PROJECT_DATA>\n" + PlanningPromptText.escapeUntrusted(
                "项目=" + project + "\n成员=" + members + "\n已有里程碑=" + milestones + "\n未完成任务=" + tasks)
                + "\n</PROJECT_DATA>\n<SOURCES>\n" + PlanningPromptText.escapeUntrusted(sources.toString()) + "\n</SOURCES>";
        return new PlanningContext(prompt, List.copyOf(sources));
    }

    private List<SeedEvidenceInput> parseSeedInputs(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            List<SeedEvidenceInput> parsed = json.readValue(rawJson, new TypeReference<>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception invalidStoredInput) {
            return List.of();
        }
    }

    public int effectiveMaxSources() {
        int configured = systemConfigService != null
                ? systemConfigService.getRagMaxSources()
                : properties.maxSources();
        return Math.min(MAX_PLANNING_SOURCES, Math.max(1, configured));
    }

    public int sourceCodepointBudget() {
        return properties.sourceCodepointBudget();
    }

    public record PlanningContext(String promptText, List<PlanSource> sources) {}

    public MemberSnapshot memberSnapshot(UUID projectId) {
        var members = jdbc.queryForList("""
                SELECT u.id,u.display_name,pm.role FROM project_member pm JOIN app_user u ON u.id=pm.user_id
                WHERE pm.project_id=? ORDER BY u.id
                """, projectId);
        Set<UUID> memberIds = members.stream()
                .map(row -> row.get("id"))
                .map(value -> value instanceof UUID id ? id : UUID.fromString(value.toString()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new MemberSnapshot("项目成员（仅可推荐以下成员作为负责人）：" + members, memberIds);
    }

    public record MemberSnapshot(String contextText, Set<UUID> memberIds) {}
}
