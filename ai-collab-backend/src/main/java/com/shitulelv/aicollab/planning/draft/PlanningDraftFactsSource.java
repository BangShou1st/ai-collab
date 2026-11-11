package com.shitulelv.aicollab.planning.draft;

import com.shitulelv.aicollab.planning.domain.ValidationContext;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Planning draft shadow 的项目事实读取（只读）。
 *
 * <p>只查 {@code project} / {@code project_member} / {@code app_user} / {@code milestone} /
 * {@code project_task} 这五张既有业务表，<b>不写库</b>；查询数量固定（5 条），
 * 每条都带 LIMIT，供影子请求构造有界快照。成员名单只映射 id + display_name，
 * 不读取邮箱、账号等字段。</p>
 */
@Component
public class PlanningDraftFactsSource {

    private static final int MAX_MEMBERS = 50;
    private static final int MAX_TITLES = 40;

    private final JdbcTemplate jdbc;

    public PlanningDraftFactsSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取有界项目事实；项目不存在时返回空名称/描述而不是抛错（由调用方决定跳过）。 */
    public PlanFacts load(TaskPlanRecord plan, ValidationContext context) {
        UUID projectId = plan.projectId();
        String name = "";
        String description = "";
        List<String[]> projectRows = jdbc.query(
                "SELECT name, description FROM project WHERE id=?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, projectId);
        if (!projectRows.isEmpty()) {
            name = projectRows.getFirst()[0] == null ? "" : projectRows.getFirst()[0];
            description = projectRows.getFirst()[1] == null ? "" : projectRows.getFirst()[1];
        }

        List<MemberFact> members = new ArrayList<>();
        List<Object[]> memberRows = jdbc.query(
                """
                SELECT pm.user_id, u.display_name FROM project_member pm
                  JOIN app_user u ON u.id = pm.user_id
                 WHERE pm.project_id=? ORDER BY pm.joined_at, pm.user_id LIMIT ?
                """,
                (rs, row) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)},
                projectId, MAX_MEMBERS);
        for (Object[] row : memberRows) {
            UUID memberId = (UUID) row[0];
            if (context == null || context.projectMemberIds().contains(memberId)) {
                members.add(new MemberFact(memberId, row[1] == null ? "" : (String) row[1]));
            }
        }

        List<String> milestoneTitles = jdbc.queryForList(
                "SELECT name FROM milestone WHERE project_id=? ORDER BY sort_order, created_at LIMIT ?",
                String.class, projectId, MAX_TITLES);
        List<String> taskTitles = jdbc.queryForList(
                "SELECT title FROM project_task WHERE project_id=? ORDER BY created_at DESC LIMIT ?",
                String.class, projectId, MAX_TITLES);
        return new PlanFacts(name, description, List.copyOf(members), List.copyOf(milestoneTitles),
                List.copyOf(taskTitles));
    }

    /** 影子请求所需的项目事实（全部有界）。 */
    public record PlanFacts(
            String projectName,
            String description,
            List<MemberFact> members,
            List<String> existingMilestoneTitles,
            List<String> existingTaskTitles) {
    }

    /** 成员事实：只含 id 与显示名。 */
    public record MemberFact(UUID id, String name) {
    }
}
