package com.shitulelv.aicollab.agent.application;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.common.exception.*;
import com.shitulelv.aicollab.planning.application.*;
import com.shitulelv.aicollab.planning.api.*;
import com.shitulelv.aicollab.planning.infrastructure.*;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AgentPlanningOperationService {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final ProjectAccessGuard access;
    private final TaskPlanCommandService commands; private final TaskPlanRepository plans; private final Validator validator;
    public AgentPlanningOperationService(JdbcTemplate jdbc,ObjectMapper json,ProjectAccessGuard access,TaskPlanCommandService commands,TaskPlanRepository plans,Validator validator) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.commands=commands;this.plans=plans;this.validator=validator;
    }
    @Transactional
    public ObjectNode mutate(AgentToolContext ctx,String tool,JsonNode args) {
        access.requireAdmin(ctx.projectId(),ctx.userId());
        if(ctx.invocationId()==null || ctx.scheduled() || ctx.depth()!=0) throw new BusinessException(ErrorCode.AGENT_TOOL_NOT_ALLOWED);
        var identities=jdbc.queryForList("SELECT tool_name,arguments_json::text FROM agent_tool_invocation WHERE invocation_id=? AND run_id=? FOR UPDATE",ctx.invocationId(),ctx.runId());
        if(identities.size()!=1 || !tool.equals(identities.getFirst().get("tool_name")) || !args.equals(parse(identities.getFirst().get("arguments_json").toString())))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,"工具调用身份与参数冲突");
        var existing=jdbc.queryForList("SELECT id FROM agent_planning_operation WHERE invocation_id=?",UUID.class,ctx.invocationId());
        if(!existing.isEmpty()) return get(ctx.projectId(),existing.getFirst(),ctx.userId());
        var run=jdbc.queryForMap("SELECT r.session_id,r.requester_id,r.status,r.cancel_requested_at,s.working_state->>'goalRevision' AS goal_revision FROM agent_run r JOIN agent_session s ON s.id=r.session_id WHERE r.project_id=? AND r.id=? FOR UPDATE OF r",ctx.projectId(),ctx.runId());
        if(!ctx.userId().equals(run.get("requester_id")) || run.get("cancel_requested_at")!=null || !Set.of("RUNNING","QUEUED").contains(run.get("status"))) throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED);
        TaskPlanRecord plan; UUID targetVersion=null; UUID ownedAttempt;
        if("start_task_plan".equals(tool)) {
            // Bound repeated starts in one run even with a different model-generated call identity.
            if(jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation WHERE origin_run_id=? AND kind='start_task_plan'",Integer.class,ctx.runId())>0)
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,"本轮已启动规划，请读取原操作");
            var request=convert(args,CreateTaskPlanRequest.class); validate(request);
            plan=commands.create(ctx.projectId(),request,ctx.userId()); ownedAttempt=plan.activeAttemptId();
        } else if("repair_task_plan".equals(tool)) {
            UUID planId=uuid(args,"planId"); var request=convert(args.path("repair"),PartialRegenerateRequest.class);validate(request);
            if(request.targetTempKeys().size()>40 || request.issueIds().size()>100 || request.allowedFields().size()>20 || request.lockedFields().size()>20) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"修复范围过大");
            targetVersion=request.baseVersionId();plan=commands.partialRegenerate(ctx.projectId(),planId,request,ctx.userId());ownedAttempt=plan.activeAttemptId();
        } else if("cancel_task_plan_generation".equals(tool)) {
            UUID target=uuid(args,"operationId");
            var operation=jdbc.queryForMap("SELECT plan_id,generation_seq FROM agent_planning_operation WHERE id=? AND project_id=?",target,ctx.projectId());
            plan=plans.lock(ctx.projectId(),(UUID)operation.get("plan_id"));
            if(plan.generationSeq()!=((Number)operation.get("generation_seq")).longValue() || !Objects.equals(plan.activeAttemptId(),uuid(args,"attemptId"))) throw new BusinessException(ErrorCode.PLAN_VERSION_CONFLICT,"生成任务已切换，请刷新操作状态");
            ownedAttempt=plan.activeAttemptId();plan=commands.cancel(ctx.projectId(),plan.id(),ctx.userId());
        } else throw new BusinessException(ErrorCode.AGENT_TOOL_NOT_ALLOWED);
        UUID operation=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_planning_operation(id,invocation_id,project_id,requester_id,session_id,origin_run_id,goal_revision,kind,request_json,target_version_id,plan_id,attempt_id,generation_seq) VALUES (?,?,?,?,?,?,?, ?,?::jsonb,?,?,?,?)",operation,ctx.invocationId(),ctx.projectId(),ctx.userId(),run.get("session_id"),ctx.runId(),Integer.parseInt(Objects.toString(run.get("goal_revision"),"0")),tool,args.toString(),targetVersion,plan.id(),ownedAttempt,plan.generationSeq());
        return get(ctx.projectId(),operation,ctx.userId());
    }
    public ObjectNode get(UUID project,UUID operation,UUID user) {
        access.requireMember(project,user);
        synchronizeOperations(project, null, operation);
        return readOperation(project, operation);
    }
    private ObjectNode readOperation(UUID project, UUID operation) {
        var rows=jdbc.queryForList(OPERATION_SELECT+" WHERE o.project_id=? AND o.id=?",project,operation);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.TASK_PLAN_NOT_FOUND);
        return operationJson(project,rows.getFirst());
    }
    private static final String EXECUTION_JOIN="""
        LEFT JOIN LATERAL (
          SELECT x.id,x.status,x.stage,x.error_code,x.finished_at FROM ai_task_plan_attempt x
          WHERE x.plan_id=o.plan_id AND x.generation_seq=o.generation_seq
            AND (o.kind='start_task_plan' OR x.id=o.attempt_id)
            AND x.attempt_no>=a.attempt_no
          ORDER BY CASE WHEN x.stage='DETAIL' THEN 0 ELSE 1 END,x.attempt_no LIMIT 1
        ) execution ON true
        """;
    // A failed operation without a result version must not borrow a later generation's version.
    private static final String OPERATION_SELECT="SELECT o.id,o.plan_id,o.attempt_id,o.kind,o.status,o.goal_revision,o.target_version_id,o.result_version_id,p.title,coalesce(v.version_no,0) AS latest_version_no,CASE WHEN p.generation_seq=o.generation_seq THEN p.active_attempt_id END AS active_attempt_id,coalesce(execution.status,a.status) AS attempt_status,coalesce(a.error_code,execution.error_code) AS error_code FROM agent_planning_operation o JOIN ai_task_plan p ON p.id=o.plan_id LEFT JOIN ai_task_plan_attempt a ON a.id=o.attempt_id LEFT JOIN ai_task_plan_version v ON v.id=o.result_version_id " + EXECUTION_JOIN;
    private ObjectNode operationJson(UUID project,Map<String,Object> row) {
        var value=json.createObjectNode();
        for(String field:List.of("status","kind"))value.put(field,Objects.toString(row.get(field),null));
        value.put("operationId",row.get("id").toString());value.put("planId",row.get("plan_id").toString());
        value.put("attemptId",Objects.toString(row.get("attempt_id"),null));value.put("activeAttemptId",Objects.toString(row.get("active_attempt_id"),null));
        value.put("attemptStatus",Objects.toString(row.get("attempt_status"),null));value.put("errorCode",Objects.toString(row.get("error_code"),null));
        value.put("title",Objects.toString(row.get("title"),null));value.put("versionNo",((Number)row.get("latest_version_no")).intValue());
        value.put("versionId",Objects.toString(row.get("result_version_id"),null));value.put("targetVersionId",Objects.toString(row.get("target_version_id"),null));
        value.put("goalRevision",((Number)row.get("goal_revision")).intValue());value.put("effect","PLANNING_OPERATION_ACCEPTED");value.put("requiresHumanConfirmation",true);
        value.put("reviewPath","/projects/"+project+"/ai-planning?planId="+row.get("plan_id")); return value;
    }
    public List<ObjectNode> list(UUID project,UUID session,UUID user) {
        access.requireMember(project,user);
        synchronizeOperations(project, session, null);
        var rows=jdbc.queryForList(OPERATION_SELECT+" JOIN agent_session s ON s.id=o.session_id WHERE o.project_id=? AND s.project_id=? AND o.session_id=? ORDER BY o.created_at DESC,o.id DESC LIMIT 50",project,project,session);
        return rows.stream().map(row->operationJson(project,row)).toList();
    }
    /** Derived from persistent plan/attempt state. One atomic update+event; terminal operation results never follow a newer target. */
    @Transactional public void synchronizeOperations() {
        synchronizeOperations(null, null, null);
    }
    private void synchronizeOperations(UUID project, UUID session, UUID operation) {
        jdbc.update("""
            WITH states AS (
              SELECT o.id,CASE WHEN a.status IN ('FAILED','CANCELED','DISCARDED') THEN a.status
                WHEN owned.id IS NOT NULL THEN CASE WHEN jsonb_array_length(coalesce(owned.validation_result_json->'errors','[]'::jsonb))>0 THEN 'READY_WITH_ISSUES' ELSE 'READY' END
                WHEN execution.error_code='PLAN_GENERATION_CANCELED' THEN 'CANCELED'
                WHEN execution.status='FAILED' AND execution.stage='DETAIL' THEN 'DETAIL_GENERATION_FAILED'
                WHEN execution.status IN ('FAILED','CANCELED','DISCARDED') THEN execution.status
                WHEN p.generation_seq<>o.generation_seq THEN 'SUPERSEDED' ELSE p.status END AS status,
                coalesce(owned.id,CASE WHEN execution.stage='DETAIL' AND execution.status IN ('FAILED','CANCELED','DISCARDED')
                  THEN (SELECT v.id FROM ai_task_plan_version v WHERE v.plan_id=o.plan_id AND v.generation_seq=o.generation_seq AND v.source_type='AI_SKELETON' ORDER BY v.version_no LIMIT 1)
                  WHEN p.generation_seq=o.generation_seq AND p.status NOT IN ('SKELETON_GENERATING','DETAIL_GENERATING','REPAIRING') THEN p.latest_version_id END) AS version
              FROM agent_planning_operation o JOIN ai_task_plan p ON p.id=o.plan_id LEFT JOIN ai_task_plan_attempt a ON a.id=o.attempt_id
              LEFT JOIN LATERAL (
                SELECT x.status,x.stage,x.error_code,x.finished_at FROM ai_task_plan_attempt x
                WHERE x.plan_id=o.plan_id AND x.generation_seq=o.generation_seq
                  AND (o.kind='start_task_plan' OR x.id=o.attempt_id)
                  AND x.attempt_no>=a.attempt_no
                ORDER BY CASE WHEN x.stage='DETAIL' THEN 0 ELSE 1 END,x.attempt_no LIMIT 1
              ) execution ON true
              LEFT JOIN LATERAL (
                SELECT v.id,v.validation_result_json FROM ai_task_plan_version v
                WHERE v.plan_id=o.plan_id AND v.generation_seq=o.generation_seq
                  AND (execution.finished_at IS NULL OR v.created_at<=execution.finished_at)
                  AND ((o.kind='start_task_plan' AND v.source_type IN ('AI_COMPLETE','AI_REPAIR','AI_PARTIAL'))
                    OR (o.kind='repair_task_plan' AND v.source_type='AI_PARTIAL_REPAIR' AND v.based_on_version_id=o.target_version_id))
                ORDER BY v.version_no DESC LIMIT 1
              ) owned ON true
              WHERE (o.status IN ('ACCEPTED','SKELETON_GENERATING','DETAIL_GENERATING','REPAIRING')
                OR (o.status='SUPERSEDED' AND execution.error_code='PLAN_GENERATION_CANCELED'))
                AND (?::uuid IS NULL OR o.project_id=?::uuid)
                AND (?::uuid IS NULL OR o.session_id=?::uuid)
                AND (?::uuid IS NULL OR o.id=?::uuid)
            ), changed AS (
              UPDATE agent_planning_operation o SET status=s.status,result_version_id=s.version,updated_at=now()
              FROM states s WHERE o.id=s.id AND (o.status IS DISTINCT FROM s.status OR o.result_version_id IS DISTINCT FROM s.version)
                AND o.status IN ('ACCEPTED','SKELETON_GENERATING','DETAIL_GENERATING','REPAIRING','SUPERSEDED')
              RETURNING o.id,o.status,o.result_version_id
            ) INSERT INTO agent_planning_operation_event(operation_id,status,result_version_id) SELECT id,status,result_version_id FROM changed
            """, project, project, session, session, operation, operation);
    }
    private void validate(Object request){
        var violations=validator.validate(request);
        if(violations.isEmpty())return;
        // 字段路径来自代码作者定义的 DTO 约束（如 maxTaskCount/planDueDate），
        // 是模型纠正参数所需的最小信息；不透出内部异常细节。
        var fields=violations.stream().map(v->v.getPropertyPath().toString()).distinct().limit(6).sorted().toList();
        throw new BusinessException(ErrorCode.VALIDATION_ERROR,"规划参数校验失败："+String.join(",",fields));
    }
    private <T>T convert(JsonNode node,Class<T> type){try{return json.treeToValue(node,type);}catch(Exception ex){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"规划参数格式无效");}}
    private JsonNode parse(String text){try{return json.readTree(text);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private UUID uuid(JsonNode node,String field){try{return UUID.fromString(node.path(field).asText());}catch(Exception ex){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"缺少明确对象身份："+field);}}
}
