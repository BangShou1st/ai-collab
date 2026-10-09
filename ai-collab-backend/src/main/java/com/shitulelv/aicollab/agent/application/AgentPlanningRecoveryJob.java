package com.shitulelv.aicollab.agent.application;
import com.shitulelv.aicollab.planning.application.*;
import com.shitulelv.aicollab.planning.infrastructure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Queued attempts are a durable dispatch intent; markRunning fences duplicate dispatches. */
@Component
public class AgentPlanningRecoveryJob {
    private final JdbcTemplate jdbc; private final TaskPlanRepository plans; private final TaskPlanGenerationOrchestrator generation;
    private final TaskPlanPartialRepairService repairs;private final AgentPlanningOperationService operations;
    public AgentPlanningRecoveryJob(JdbcTemplate jdbc,TaskPlanRepository plans,TaskPlanGenerationOrchestrator generation,TaskPlanPartialRepairService repairs,AgentPlanningOperationService operations){
        this.jdbc=jdbc;this.plans=plans;this.generation=generation;this.repairs=repairs;this.operations=operations;
    }
    @org.springframework.beans.factory.annotation.Value("${agent.enabled:false}") private boolean enabled;
    @Scheduled(fixedDelayString="${agent.planning.recovery-delay-ms:5000}")
    public void tick(){if(enabled) recover();}
    public void recover() {
        var rows=jdbc.queryForList("""
            SELECT DISTINCT p.id,p.project_id,a.id AS attempt_id,a.created_by,a.stage
            FROM agent_planning_operation o JOIN ai_task_plan p ON p.id=o.plan_id JOIN ai_task_plan_attempt a ON a.id=p.active_attempt_id
            WHERE a.status='QUEUED' AND a.updated_at<now()-interval '5 seconds'
              AND p.generation_seq=o.generation_seq AND p.status IN ('SKELETON_GENERATING','DETAIL_GENERATING','REPAIRING') LIMIT 20
            """);
        for(var row:rows) {
            try {
                if("REPAIR".equals(row.get("stage"))) repairs.resume((UUID)row.get("attempt_id"));
                else generation.dispatch(plans.require((UUID)row.get("project_id"),(UUID)row.get("id")),(UUID)row.get("created_by"),"DETAIL".equals(row.get("stage")));
            } catch(RuntimeException failure) { /* existing attempts and bounded stale recovery retain the failure */ }
        }
        operations.synchronizeOperations();
    }
}
