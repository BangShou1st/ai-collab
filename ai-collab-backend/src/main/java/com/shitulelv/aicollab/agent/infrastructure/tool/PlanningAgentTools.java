package com.shitulelv.aicollab.agent.infrastructure.tool;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.AgentPlanningOperationService;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.planning.application.TaskPlanQueryService;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Configuration
@ConditionalOnProperty(name="agent.capabilities.planning",havingValue="true",matchIfMissing=true)
public class PlanningAgentTools {
    private final AgentPlanningOperationService operations;private final TaskPlanQueryService queries;private final ObjectMapper json;
    public PlanningAgentTools(AgentPlanningOperationService operations,TaskPlanQueryService queries,ObjectMapper json){this.operations=operations;this.queries=queries;this.json=json;}
    @Bean AgentTool listTaskPlans(){return read("list_task_plans","查询现有规划列表，page 从 0 开始，下一页继续查询；结果不足 size 时结束。", """
        {"type":"object","additionalProperties":false,"properties":{"status":{"type":"string"},"page":{"type":"integer","minimum":0,"maximum":10000},"size":{"type":"integer","minimum":1,"maximum":50}}}
        """,(ctx,args)->{AgentToolArguments.requireFields(args,Set.of("status","page","size"));int page=AgentToolArguments.integer(args,"page",0,0,10000);int size=AgentToolArguments.integer(args,"size",20,1,50);String status=AgentToolArguments.text(args,"status",40,false);if(status!=null)com.shitulelv.aicollab.planning.domain.TaskPlanStatus.valueOf(status);var items=queries.list(ctx.projectId(),status,page,size,ctx.userId());var result=json.createObjectNode();result.set("items",json.valueToTree(items));result.put("page",page);result.put("size",size);result.put("hasMore",items.size()==size);result.put("nextPage",page+1);return result;});}
    @Bean AgentTool getTaskPlan(){return read("get_task_plan","读取规划详情、版本、问题及来源。versionId 可指定历史版本；不替用户确认正式规划。", """
        {"type":"object","additionalProperties":false,"required":["planId"],"properties":{"planId":{"type":"string","format":"uuid"},"versionId":{"type":"string","format":"uuid"},"fromTask":{"type":"integer","minimum":0,"maximum":40},"taskLimit":{"type":"integer","minimum":1,"maximum":5}}}
        """,this::planDetail);}
    @Bean AgentTool getTaskPlanProgress(){return read("get_task_plan_progress","查询对应操作真实状态；不循环轮询，受理后由后台更新卡片。", """
        {"type":"object","additionalProperties":false,"required":["operationId"],"properties":{"operationId":{"type":"string","format":"uuid"}}}
        """,(ctx,args)->{AgentToolArguments.requireFields(args,Set.of("operationId"));return operations.get(ctx.projectId(),AgentToolArguments.uuid(args,"operationId",true),ctx.userId());});}
    @Bean AgentTool startTaskPlan(){return write("start_task_plan","管理员生成完整规划草稿，消耗 PLANNING 额度。先查资料及业务事实，再填写日期/限制；仅受理，不是生成成功。不得用多次单任务提案替代完整规划。", """
        {"type":"object","additionalProperties":false,"required":["title","goal","planStartDate","planDueDate","maxTaskCount"],"properties":{"title":{"type":"string","minLength":1,"maxLength":160},"goal":{"type":"string","minLength":1,"maxLength":2000},"constraints":{"type":"string","maxLength":4000},"planStartDate":{"type":"string","format":"date"},"planDueDate":{"type":"string","format":"date"},"maxTaskCount":{"type":"integer","minimum":1,"maximum":40},"documentIds":{"type":"array","maxItems":10,"uniqueItems":true,"items":{"type":"string","format":"uuid"}}}}
        """,Set.of("title","goal","constraints","planStartDate","planDueDate","maxTaskCount","documentIds"));}
    @Bean AgentTool repairTaskPlan(){return write("repair_task_plan","管理员按指定 baseVersionId/expectedVersionNo 和范围局部修复同一规划。必须先查版本与 tempKey；lockedFields 禁止修改日期等锁定字段。异步受理后不轮询。", """
        {"type":"object","additionalProperties":false,"required":["planId","repair"],"properties":{"planId":{"type":"string","format":"uuid"},"repair":{"type":"object","additionalProperties":false,"required":["baseVersionId","expectedVersionNo","mode"],"properties":{"baseVersionId":{"type":"string","format":"uuid"},"expectedVersionNo":{"type":"integer","minimum":1},"mode":{"type":"string"},"userInstructions":{"type":"string","maxLength":2000,"description":"本轮用户修订意图；仅影响已授权目标与字段，不能改变锁定字段"},"targetTempKeys":{"type":"array","maxItems":40,"items":{"type":"string"}},"allowedFields":{"type":"array","maxItems":20,"items":{"type":"string"}},"lockedFields":{"type":"array","maxItems":20,"items":{"type":"string"}},"issueIds":{"type":"array","maxItems":100,"items":{"type":"string","format":"uuid"}}}}}}
        """,Set.of("planId","repair"));}
    @Bean AgentTool cancelTaskPlanGeneration(){return write("cancel_task_plan_generation","管理员取消指定操作的当前 attemptId，须先读取真实状态，不能取消另一个生成任务。保留已有版本。", """
        {"type":"object","additionalProperties":false,"required":["operationId","attemptId"],"properties":{"operationId":{"type":"string","format":"uuid"},"attemptId":{"type":"string","format":"uuid"}}}
        """,Set.of("operationId","attemptId"));}
    private AgentTool write(String name,String description,String schema,Set<String> fields){return new ControlledWriteAgentTool(){
        public String name(){return name;}public AgentToolDefinition definition(){return AgentToolDefinition.fromJson(name,description,schema,true);}
        public AgentToolResult execute(AgentToolContext ctx,JsonNode args){AgentToolArguments.requireFields(args,fields);return new AgentToolResult(operations.mutate(ctx,name,args),List.of(),List.of());}
    };}
    private interface Read {JsonNode apply(AgentToolContext ctx,JsonNode args);}
    private JsonNode planDetail(AgentToolContext ctx,JsonNode args) {
        AgentToolArguments.requireFields(args,Set.of("planId","versionId","fromTask","taskLimit"));
        var plan=AgentToolArguments.uuid(args,"planId",true);var version=AgentToolArguments.uuid(args,"versionId",false);
        int from=AgentToolArguments.integer(args,"fromTask",0,0,40),limit=AgentToolArguments.integer(args,"taskLimit",5,1,5);
        var result=json.createObjectNode();JsonNode detail=json.valueToTree(queries.detail(ctx.projectId(),plan,ctx.userId()));result.set("detail",detail);
        var versions=queries.versions(ctx.projectId(),plan,ctx.userId());var metadata=result.putArray("versions");
        versions.stream().limit(20).forEach(v->metadata.add(versionMetadata(v)));
        result.put("versionsTruncated",versions.size()>20);
        if(version==null && detail.path("latestVersion").hasNonNull("id")) version=UUID.fromString(detail.path("latestVersion").path("id").asText());
        if(version!=null) {
            var selected=queries.version(ctx.projectId(),plan,version,ctx.userId());
            result.set("version",versionMetadata((com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord)selected.get("version")));
            result.put("baseVersionId",version.toString());result.put("expectedVersionNo",result.path("version").path("versionNo").asInt());
            ObjectNode draft=json.valueToTree(selected.get("draft"));var tasks=draft.withArray("tasks");var page=json.createArrayNode();
            for(int i=from;i<Math.min(tasks.size(),from+limit);i++) page.add(tasks.get(i));
            result.put("totalTasks",tasks.size());result.put("fromTask",from);result.put("hasMore",from+page.size()<tasks.size());result.put("nextFromTask",from+page.size());
            draft.set("tasks",page);result.set("draft",draft);result.put("coverage","TASK_PAGE_AND_PLAN_CONTEXT");
        }
        return result;
    }
    private ObjectNode versionMetadata(com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord v) {
        var node=json.createObjectNode();node.put("id",v.id().toString());node.put("versionNo",v.versionNo());node.put("sourceType",v.sourceType());node.put("basedOnVersionId",Objects.toString(v.basedOnVersionId(),null));node.put("createdAt",v.createdAt().toString());return node;
    }
    private AgentTool read(String name,String description,String schema,Read read){return new AgentTool(){
        public String name(){return name;}public boolean writesBusinessData(){return false;}public AgentToolDefinition definition(){return AgentToolDefinition.fromJson(name,description,schema,false);}
        public AgentToolResult execute(AgentToolContext ctx,JsonNode args){return new AgentToolResult(read.apply(ctx,args),List.of(),List.of());}
    };}
}
