package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import com.shitulelv.aicollab.planning.domain.PlanningPromptText;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class TaskPlanContextAssembler {
    private final JdbcTemplate jdbc;
    private final ProjectKnowledgeRetrieval retrieval;
    private final ObjectMapper json;
    private final PlanningModelProperties properties;

    public TaskPlanContextAssembler(JdbcTemplate jdbc, ProjectKnowledgeRetrieval retrieval,
                                    ObjectMapper json, PlanningModelProperties properties) {
        this.jdbc = jdbc; this.retrieval = retrieval; this.json = json; this.properties = properties;
    }

    public PlanningContext assemble(TaskPlanRecord plan) {
        List<UUID> documentIds;
        try { documentIds = json.readValue(plan.selectedDocumentIdsJson(), new TypeReference<>() {}); }
        catch (Exception invalidStoredInput) { throw new IllegalStateException("Invalid selected documents", invalidStoredInput); }
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
        // 权限收紧：以 plan.createdBy() 作为检索人强制项目成员校验；非成员时失败关闭，
        // 不再像旧实现那样绕过成员校验直接检索（无 DB 迁移，行为变化见 J0-4 报告）。
        var hits = documentIds.isEmpty() ? List.<com.shitulelv.aicollab.document.application.view.DocumentSearchHit>of()
                : retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                        plan.projectId(), plan.createdBy(),
                        plan.title() + "\n" + plan.goal() + "\n" + plan.constraints(),
                        documentIds, Math.min(20, Math.max(1, properties.maxSources())),
                        ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY)).hits();
        List<PlanSource> sources = new ArrayList<>();
        HashSet<String> hashes = new HashSet<>();
        int remaining = properties.sourceCodepointBudget();
        for (var hit : hits) {
            if (sources.size() >= properties.maxSources() || hit.similarity() < properties.retrievalThreshold()
                    || !hashes.add(hit.contentHash()) || hit.content() == null) continue;
            int length = hit.content().codePointCount(0, hit.content().length());
            if (length > remaining) break;
            sources.add(new PlanSource("S" + (sources.size() + 1), hit.documentId(),
                    hit.originalFilename(), hit.id(), hit.heading(), hit.similarity(),
                    hit.content(), hit.contentHash()));
            remaining -= length;
        }
        String prompt = "<PROJECT_DATA>\n" + PlanningPromptText.escapeUntrusted(
                "项目=" + project + "\n成员=" + members + "\n已有里程碑=" + milestones + "\n未完成任务=" + tasks)
                + "\n</PROJECT_DATA>\n<SOURCES>\n" + PlanningPromptText.escapeUntrusted(sources.toString()) + "\n</SOURCES>";
        return new PlanningContext(prompt, List.copyOf(sources));
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
