package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.api.PartialRegenerateRequest;
import com.shitulelv.aicollab.planning.api.SaveTaskPlanVersionRequest;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanIssueRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanVersionRecord;
import com.shitulelv.aicollab.work.application.service.WorkReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TaskPlanSpringBeanPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    private static final String MINIO_ACCESS_KEY = "phase08-access";
    private static final String MINIO_SECRET_KEY = "phase08-secret-key";
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("storage.minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("storage.minio.secret-key", () -> MINIO_SECRET_KEY);
    }

    @Autowired TaskPlanCommandService commands;
    @Autowired TaskPlanConfirmationService confirmations;
    @Autowired TaskPlanPartialRepairService partialRepair;
    @Autowired TaskPlanVersionCommitService commits;
    @Autowired TaskPlanGenerationOrchestrator orchestrator;
    @Autowired TaskPlanRepository repository;
    @Autowired TaskPlanQueryService queries;
    @Autowired TaskPlanIssueRepository issues;
    @Autowired TaskPlanActionPolicy actionPolicy;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkReportService workReports;
    @Autowired com.shitulelv.aicollab.agent.application.AgentPlanningOperationService agentOperations;
    @Autowired com.shitulelv.aicollab.agent.application.AgentPlanningRecoveryJob agentRecovery;
    @Autowired com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository agents;
    @Autowired com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry agentTools;
    @MockitoBean TaskPlanModelClient model;
    @Autowired PlanningModelConfigurationStore planningConfigurations;

    @Test void planningConfigurationIdentitySurvivesReloadAndRejectsEdits() {
        var f=fixture("planning-config"); UUID id=UUID.randomUUID(), generation=UUID.randomUUID();
        jdbc.update("INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,model_name,is_default,enabled) VALUES (?,?,'planning-test','OPENAI_COMPATIBLE','https://example.com','fixture-model',true,true)",id,f.user());
        var pinned=planningConfigurations.require(f.user(),generation,1200);
        assertThat(pinned.id()).isEqualTo(id);
        assertThat(planningConfigurations.require(f.user(),generation,1200).id()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT snapshot->>'maxOutputTokens' FROM planning_model_snapshot WHERE generation_id=?",String.class,generation)).isEqualTo("1200");
        jdbc.update("UPDATE user_ai_provider SET model_name='changed',updated_at=updated_at+interval '1 second' WHERE id=?",id);
        assertThatThrownBy(()->planningConfigurations.require(f.user(),generation,1200)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning_model_snapshot WHERE generation_id=?",Integer.class,generation)).isEqualTo(1);
    }

    @BeforeEach
    void resetModel() {
        reset(model);
    }

    @Test void queuedDetailRecoveryAfterRevocationDoesNotCallModelOrReplaceSkeleton() throws Exception {
        var f=fixture("detail-revoked");
        var session=agents.createSession(f.project(),f.user(),"详情排队");
        var request=json.valueToTree(request("排队详情"));
        var ctx=agentInvocation(f,session.id(),"start_task_plan",request);
        var plan=repository.create(f.project(),f.user(),request("排队详情"));
        var draft=json.readValue(skeleton(),TaskPlanDraft.class);
        UUID version=repository.appendVersion(f.project(),plan.id(),null,"AI_SKELETON",null,draft,f.user(),null,TaskPlanStatus.DETAIL_GENERATING);
        UUID attempt=repository.startDetailAfterSkeleton(f.project(),plan.id(),f.user());
        UUID operation=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_planning_operation(id,invocation_id,project_id,requester_id,session_id,origin_run_id,goal_revision,kind,request_json,plan_id,attempt_id,generation_seq) VALUES (?,?,?,?,?,?,1,'start_task_plan',?::jsonb,?,?,?)",operation,ctx.invocationId(),f.project(),f.user(),session.id(),ctx.runId(),request.toString(),plan.id(),plan.activeAttemptId(),plan.generationSeq());
        jdbc.update("UPDATE ai_task_plan_attempt SET updated_at=now()-interval '1 minute' WHERE id=?",attempt);
        jdbc.update("DELETE FROM project_member WHERE project_id=? AND user_id=?",f.project(),f.user());
        agentRecovery.recover();
        var failed=awaitStatus(f.project(),plan.id(),Set.of(TaskPlanStatus.DETAIL_GENERATION_FAILED));
        agentRecovery.recover();
        org.mockito.Mockito.verify(model,org.mockito.Mockito.never()).generate(anyString(),anyString(),anyString(),any(),any(),any(),any());
        assertThat(failed.latestVersionId()).isEqualTo(version);
        assertThat(repository.versions(f.project(),plan.id())).hasSize(1);
        assertThat(repository.draft(repository.requireVersion(f.project(),plan.id(),version))).isEqualTo(draft);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task_plan_attempt WHERE id=?",String.class,attempt)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ai_task_plan_attempt WHERE id=?",String.class,attempt)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT status FROM agent_planning_operation WHERE id=?",String.class,operation)).isEqualTo("DETAIL_GENERATION_FAILED");
    }

    @Autowired com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService providers;
    @Test void operationListSynchronizesOnlyRequestedProjectAndSession() {
        var f=fixture("list-scope"); var other=fixture("other-project");
        var selected=agents.createSession(f.project(),f.user(),"selected");
        var unselected=agents.createSession(f.project(),f.user(),"unselected");
        var foreign=agents.createSession(other.project(),other.user(),"foreign");
        UUID selectedOp=readyOperation(f,selected.id()), unselectedOp=readyOperation(f,unselected.id()), foreignOp=readyOperation(other,foreign.id());
        var listed=agentOperations.list(f.project(),selected.id(),f.user());
        assertThat(listed).hasSize(1); assertThat(listed.getFirst().path("operationId").asText()).isEqualTo(selectedOp.toString());
        assertThat(listed.getFirst().path("status").asText()).isEqualTo("READY");
        for(UUID untouched:List.of(unselectedOp,foreignOp)) {
            assertThat(jdbc.queryForObject("SELECT status FROM agent_planning_operation WHERE id=?",String.class,untouched)).isEqualTo("ACCEPTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation_event WHERE operation_id=?",Integer.class,untouched)).isZero();
        }
    }
    private UUID readyOperation(Fixture f,UUID session) {
        var request=json.valueToTree(request("list fixture")); var ctx=agentInvocation(f,session,"start_task_plan",request);
        var plan=repository.create(f.project(),f.user(),request("list fixture"));
        repository.appendVersion(f.project(),plan.id(),null,"AI_COMPLETE",null,new TaskPlanDraft("preserved",List.of(),List.of(),List.of(),List.of(),List.of()),f.user(),null,TaskPlanStatus.READY);
        UUID operation=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_planning_operation(id,invocation_id,project_id,requester_id,session_id,origin_run_id,goal_revision,kind,request_json,plan_id,attempt_id,generation_seq) VALUES (?,?,?,?,?,?,1,'start_task_plan',?::jsonb,?,?,?)",operation,ctx.invocationId(),f.project(),f.user(),session,ctx.runId(),request.toString(),plan.id(),plan.activeAttemptId(),plan.generationSeq());
        return operation;
    }
    @Test void recoveredClientPassesPersistedOutputBudgetToActualGatewayCall() {
        var f=fixture("persisted-budget"); UUID id=UUID.randomUUID(),generation=UUID.randomUUID();
        jdbc.update("INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,model_name,is_default,enabled) VALUES (?,?,'budget-test','OPENAI_COMPATIBLE','https://example.com','fixture-model',true,true)",id,f.user());
        var routing=org.mockito.Mockito.mock(com.shitulelv.aicollab.infrastructure.ai.model.RoutingChatModelGateway.class);
        when(routing.completeWithSnapshot(any(),any(),any(),any())).thenReturn(new com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult("{}","fixture","fixture",1,1,1L));
        for(int applicationBudget:List.of(1200,9000)) {
            var props=new com.shitulelv.aicollab.planning.infrastructure.ai.PlanningModelProperties(true,"openai","https://example.com","/v1","test-only","fixture",java.time.Duration.ofSeconds(30),java.time.Duration.ofSeconds(60),0.0,applicationBudget,5,10000,0.5,10);
            var client=new TaskPlanModelClient(props,org.mockito.Mockito.mock(com.shitulelv.aicollab.infrastructure.ai.AiCallLogWriter.class),routing);
            client.configureProviders(providers);
            org.springframework.test.util.ReflectionTestUtils.setField(client,"configurationStore",planningConfigurations);
            try(var scope=client.openSnapshot()) {client.generate("system","data","DETAIL",f.user(),f.project(),UUID.randomUUID(),generation);}
        }
        var budgets=org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(routing,org.mockito.Mockito.times(2)).completeWithSnapshot(any(),any(),any(),budgets.capture());
        assertThat(budgets.getAllValues()).containsExactly(1200,1200);
    }

    @Test
    void agentPlanningOperationsReplayRepairAndManualConfirmationUseRealTransactions() throws Exception {
        var f=fixture("agent-plan");stubLegalGeneration();
        var session=agents.createSession(f.project(),f.user(),"资料规划");
        var request=json.valueToTree(request("Agent 规划"));
        var ctx=agentInvocation(f,session.id(),"start_task_plan",request);
        // 新策略（v2）根运行的独立执行额度；v1 才按 Skill 额度 24/16 落库
        assertThat(agents.findRun(f.project(),ctx.runId()).orElseThrow().maxSteps()).isEqualTo(64);
        assertThat(agents.findRun(f.project(),ctx.runId()).orElseThrow().maxToolCalls()).isEqualTo(64);
        var accepted=agentOperations.mutate(ctx,"start_task_plan",request);
        UUID operation=UUID.fromString(accepted.path("operationId").asText());UUID plan=UUID.fromString(accepted.path("planId").asText());
        var replay=agentOperations.mutate(ctx,"start_task_plan",request);
        assertThat(replay.path("operationId")).isEqualTo(accepted.path("operationId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_plan WHERE project_id=?",Integer.class,f.project())).isEqualTo(1);
        var ready=awaitStatus(f.project(),plan,Set.of(TaskPlanStatus.READY));
        var readTool=agentTools.find("get_task_plan").orElseThrow();
        var observed=readTool.execute(ctx,json.createObjectNode().put("planId",plan.toString()).put("taskLimit",1));
        var sanitized=new com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer(json).sanitize(json.valueToTree(observed));
        assertThat(sanitized.path("data").path("version").path("id").asText()).isEqualTo(ready.latestVersionId().toString());
        assertThat(sanitized.path("data").path("draft").path("tasks")).hasSize(1);
        assertThat(sanitized.path("data").path("draft").path("tasks").get(0).path("tempKey").asText()).isEqualTo("t1");
        assertThat(sanitized.path("data").path("hasMore").asBoolean()).isTrue();
        assertThat(sanitized.path("data").path("versions").get(0).has("tasksJson")).isFalse();
        var terminal=agentOperations.get(f.project(),operation,f.user());assertThat(terminal.path("status").asText()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation_event WHERE operation_id=? AND status='READY'",Integer.class,operation)).isEqualTo(1);
        agentOperations.synchronizeOperations();agentOperations.synchronizeOperations();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation_event WHERE operation_id=? AND status='READY'",Integer.class,operation)).isEqualTo(1);
        assertThatThrownBy(()->agentOperations.mutate(ctx,"start_task_plan",((com.fasterxml.jackson.databind.node.ObjectNode)request.deepCopy()).put("title","同键不同参数"))).isInstanceOf(BusinessException.class);
        var original=repository.draft(repository.requireVersion(f.project(),plan,ready.latestVersionId()));
        when(model.generate(anyString(),anyString(),eq("TASK_PLAN_REPAIR_PATCH"),any(),any(),any(),any())).thenReturn(result("{\"milestonePatches\":[],\"taskPatches\":[{\"tempKey\":\"t1\",\"description\":\"修订后的说明\"}]}"));
        var repair=json.createObjectNode().put("planId",plan.toString());
        repair.set("repair",json.valueToTree(new PartialRegenerateRequest(ready.latestVersionId(),ready.latestVersionNo(),List.of("t1"),Set.of("description"),Set.of("startDate","dueDate"),List.of(),PartialRegenerateRequest.REGENERATE_SELECTED_TASK_DETAILS)));
        var repairCtx=agentInvocation(f,session.id(),"repair_task_plan",repair);
        var repairOperation=agentOperations.mutate(repairCtx,"repair_task_plan",repair);
        var repaired=awaitStatus(f.project(),plan,Set.of(TaskPlanStatus.READY));
        assertThat(repaired.latestVersionNo()).isEqualTo(ready.latestVersionNo()+1);
        var draft=repository.draft(repository.requireVersion(f.project(),plan,repaired.latestVersionId()));
        assertThat(draft.tasks().getFirst().description()).isEqualTo("修订后的说明");
        assertThat(draft.tasks().getFirst().startDate()).isEqualTo(original.tasks().getFirst().startDate());
        assertThat(draft.tasks().getFirst().dueDate()).isEqualTo(original.tasks().getFirst().dueDate());
        assertThat(agentOperations.mutate(repairCtx,"repair_task_plan",repair).path("operationId")).isEqualTo(repairOperation.path("operationId"));
        // Simulate lost/late completion event: a subsequent repair must not become the original operation result.
        jdbc.update("UPDATE agent_planning_operation SET status='ACCEPTED',result_version_id=null WHERE id=?",operation);
        assertThat(agentOperations.get(f.project(),operation,f.user()).path("versionId").asText()).isEqualTo(ready.latestVersionId().toString());
        assertThatThrownBy(()->confirmations.confirm(f.project(),plan,ready.latestVersionId(),UUID.randomUUID(),f.user())).isInstanceOf(BusinessException.class);
        UUID key=UUID.randomUUID();var confirmed=confirmations.confirm(f.project(),plan,repaired.latestVersionId(),key,f.user());
        assertThat((com.fasterxml.jackson.databind.JsonNode)json.valueToTree(confirmations.confirm(f.project(),plan,repaired.latestVersionId(),key,f.user()))).isEqualTo(json.valueToTree(confirmed));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_task WHERE source_plan_id=?",Integer.class,plan)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_dependency d JOIN project_task t ON t.id=d.task_id WHERE t.source_plan_id=?",Integer.class,plan)).isEqualTo(1);
        // The original operation stays tied to its own result version; later revisions cannot overwrite it.
        assertThat(agentOperations.get(f.project(),operation,f.user()).path("versionId").asText()).isEqualTo(ready.latestVersionId().toString());
        assertThat(agentOperations.get(f.project(),operation,f.user()).path("versionNo").asInt()).isEqualTo(ready.latestVersionNo());
        jdbc.update("DELETE FROM project_member WHERE project_id=? AND user_id=?",f.project(),f.user());
        assertThatThrownBy(()->agentOperations.get(f.project(),operation,f.user())).isInstanceOf(BusinessException.class);
    }

    @Test
    void failedSkeletonOperationDoesNotBorrowLaterRetryVersion() throws Exception {
        var f=fixture("failed-agent-retry");
        when(model.generate(anyString(),anyString(),eq("TASK_PLAN_SKELETON"),any(),any(),any(),any()))
                .thenThrow(new BusinessException(ErrorCode.PLANNING_MODEL_INVALID_OUTPUT));
        var session=agents.createSession(f.project(),f.user(),"原失败规划");
        var request=json.valueToTree(request("原失败规划"));
        var ctx=agentInvocation(f,session.id(),"start_task_plan",request);
        var accepted=agentOperations.mutate(ctx,"start_task_plan",request);
        UUID operation=UUID.fromString(accepted.path("operationId").asText());
        UUID plan=UUID.fromString(accepted.path("planId").asText());
        awaitStatus(f.project(),plan,Set.of(TaskPlanStatus.FAILED));
        assertThat(agentOperations.get(f.project(),operation,f.user()).path("versionNo").asInt()).isZero();
        stubLegalGeneration();
        commands.regenerate(f.project(),plan,f.user());
        var ready=awaitStatus(f.project(),plan,Set.of(TaskPlanStatus.READY));
        assertThat(ready.latestVersionNo()).isEqualTo(2);
        var original=agentOperations.get(f.project(),operation,f.user());
        assertThat(original.path("status").asText()).isEqualTo("FAILED");
        assertThat(original.path("errorCode").asText()).isEqualTo("PLANNING_MODEL_INVALID_OUTPUT");
        assertThat(original.path("versionNo").asInt()).isZero();
        assertThat(original.path("versionId").isNull()).isTrue();
        assertThat(original.path("activeAttemptId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_task WHERE source_plan_id=?",Integer.class,plan)).isZero();
    }

    @Test
    void queuedAgentPlanDispatchRecoversSameAttemptWithoutDuplicateSideEffects() throws Exception {
        var f=fixture("agent-dispatch");stubLegalGeneration();var session=agents.createSession(f.project(),f.user(),"重启恢复");
        var request=json.valueToTree(request("恢复规划"));var ctx=agentInvocation(f,session.id(),"start_task_plan",request);
        // Simulate commit before dispatch using the real repository + durable operation intent.
        var plan=repository.create(f.project(),f.user(),request("恢复规划")); UUID operation=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_planning_operation(id,invocation_id,project_id,requester_id,session_id,origin_run_id,goal_revision,kind,request_json,plan_id,attempt_id,generation_seq) VALUES (?,?,?,?,?,?,1,'start_task_plan',?::jsonb,?,?,?)",operation,ctx.invocationId(),f.project(),f.user(),session.id(),ctx.runId(),request.toString(),plan.id(),plan.activeAttemptId(),plan.generationSeq());
        jdbc.update("UPDATE ai_task_plan_attempt SET updated_at=now()-interval '1 minute' WHERE id=?",plan.activeAttemptId());
        agentRecovery.recover();agentRecovery.recover();
        var ready=awaitStatus(f.project(),plan.id(),Set.of(TaskPlanStatus.READY));
        assertThat(repository.versions(f.project(),plan.id())).hasSize(2);
        assertThat(agentOperations.mutate(ctx,"start_task_plan",request).path("operationId").asText()).isEqualTo(operation.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_plan WHERE project_id=?",Integer.class,f.project())).isEqualTo(1);
        assertThat(ready.latestVersionNo()).isEqualTo(2);
    }

    @Test void agentCancelIsAttemptScopedAndMemberCannotStartPlanning() throws Exception {
        var f=fixture("agent-cancel");stubLegalGeneration();var session=agents.createSession(f.project(),f.user(),"取消规划");
        var started=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        when(model.generate(anyString(),anyString(),eq("TASK_PLAN_SKELETON"),any(),any(),any(),any())).thenAnswer(i->{started.countDown();finish.await(3,TimeUnit.SECONDS);return result(skeleton());});
        var request=json.valueToTree(request("取消规划"));var ctx=agentInvocation(f,session.id(),"start_task_plan",request);
        var op=agentOperations.mutate(ctx,"start_task_plan",request);assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
        var wrong=json.createObjectNode().put("operationId",op.path("operationId").asText()).put("attemptId",UUID.randomUUID().toString());
        var wrongCtx=agentInvocation(f,session.id(),"cancel_task_plan_generation",wrong);
        assertThatThrownBy(()->agentOperations.mutate(wrongCtx,"cancel_task_plan_generation",wrong)).isInstanceOf(BusinessException.class);
        var cancel=json.createObjectNode().put("operationId",op.path("operationId").asText()).put("attemptId",op.path("attemptId").asText());
        var cancelCtx=agentInvocation(f,session.id(),"cancel_task_plan_generation",cancel);
        var canceled=agentOperations.mutate(cancelCtx,"cancel_task_plan_generation",cancel);finish.countDown();
        assertThat(agentOperations.mutate(cancelCtx,"cancel_task_plan_generation",cancel).path("operationId")).isEqualTo(canceled.path("operationId"));
        UUID plan=UUID.fromString(op.path("planId").asText());assertThat(repository.require(f.project(),plan).status()).isEqualTo(TaskPlanStatus.CANCELED);
        assertThat(repository.versions(f.project(),plan)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_task WHERE source_plan_id=?",Integer.class,plan)).isZero();
        jdbc.update("UPDATE project_member SET role='MEMBER' WHERE project_id=? AND user_id=?",f.project(),f.user());
        var member=agentInvocation(f,session.id(),"start_task_plan",request);
        assertThatThrownBy(()->agentOperations.mutate(member,"start_task_plan",request)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_plan WHERE project_id=?",Integer.class,f.project())).isEqualTo(1);
    }

    private com.shitulelv.aicollab.agent.domain.tool.AgentToolContext agentInvocation(Fixture f,UUID session,String name,com.fasterxml.jackson.databind.JsonNode args) {
        var run=agents.createRun(f.project(),session,f.user(),"根据资料生成规划",false,"ITERATION_PLANNING",null);UUID invocation=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_tool_invocation(run_id,turn_sequence,ordinal,invocation_id,tool_call_id,tool_name,arguments_json,status) VALUES (?,1,1,?,'test-call',?,?::jsonb,'PENDING')",run.id(),invocation,name,args.toString());
        return new com.shitulelv.aicollab.agent.domain.tool.AgentToolContext(run.id(),f.project(),f.user(),"OWNER",false,0,invocation);
    }

    @Test
    void productionPlanningBeansUseSpringTransactionsAndLatestFlywaySchema() {
        assertThat(commands).isNotNull();
        assertThat(confirmations).isNotNull();
        assertThat(partialRepair).isNotNull();
        assertThat(orchestrator).isNotNull();
        assertThat(actionPolicy).isNotNull();
        assertThat(json).isNotNull();
        assertThat(repository).isNotNull();
        assertThat(issues).isNotNull();
        assertThat(AopUtils.isAopProxy(commits)).isTrue();
        // 断言"最新成功迁移至少到 V64"，而不是把版本硬编码成 63：
        // 硬编码会在每次新增迁移时无条件失败，既发现不了真问题，也掩盖真正的失败。
        assertThat(Integer.parseInt(jdbc.queryForObject(
                "select version from flyway_schema_history where success=true order by installed_rank desc limit 1",
                String.class))).isGreaterThanOrEqualTo(64);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name='ai_task_plan'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name='ai_task_plan_event'
                  and column_name='changed_targets_json'
                  and data_type='jsonb'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void createAcceptsAnyTaskLimitWithinSupportedRange() throws Exception {
        Fixture fixture = fixture("spring-custom-limit");
        stubLegalGeneration();
        CreateTaskPlanRequest request = new CreateTaskPlanRequest(
                "自定义任务数规划",
                "完成系统",
                "",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                17,
                List.of());

        var created = commands.create(fixture.project(), request, fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));

        assertThat(ready.maxTaskCount()).isEqualTo(17);
    }

    @Test
    void productionBeansGenerateLatestVersionAndConfirmFormalWork() throws Exception {
        Fixture fixture = fixture("spring-ready");
        stubLegalGeneration();

        var created = commands.create(fixture.project(), request("Spring READY"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        UUID firstVersion = ready.latestVersionId();
        TaskPlanDraft firstDraft = repository.draft(
                repository.requireVersion(fixture.project(), created.id(), firstVersion));

        UUID secondVersion = commands.save(fixture.project(), created.id(),
                new SaveTaskPlanVersionRequest(firstVersion, ready.latestVersionNo(),
                        new TaskPlanDraft(firstDraft.summary() + "（已审阅）",
                                firstDraft.assumptions(), firstDraft.risks(),
                                firstDraft.milestones(), firstDraft.tasks(), firstDraft.sources()),
                        null),
                fixture.user());

        assertThatThrownBy(() -> confirmations.confirm(
                fixture.project(), created.id(), firstVersion, UUID.randomUUID(), fixture.user()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PLAN_VERSION_CONFLICT));

        var confirmed = confirmations.confirm(
                fixture.project(), created.id(), secondVersion, UUID.randomUUID(), fixture.user());
        assertThat(confirmed.get("status")).isEqualTo("SUCCESS");
        assertThat(confirmed.get("dependencyCount")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from milestone where source_plan_id=?", Integer.class, created.id())).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from project_task where source_plan_id=?", Integer.class, created.id())).isEqualTo(2);
        assertThat(repository.require(fixture.project(), created.id()).status())
                .isEqualTo(TaskPlanStatus.CONFIRMED);
        var comparison = workReports.getPlanComparison(
                fixture.project(), created.id(), fixture.user());
        assertThat(comparison.planName()).isEqualTo("Spring READY");
        assertThat(comparison.summary().totalPlanned()).isEqualTo(2);
        assertThat(comparison.summary().matchedTasks()).isEqualTo(2);
        assertThat(comparison.summary().modifiedTasks()).isZero();
        assertThat(comparison.summary().missingTasks()).isZero();
        assertThat(comparison.summary().extraTasks()).isZero();
    }

    @Test
    void productionBeansDegradeEditAndScopedRepair() throws Exception {
        Fixture fixture = fixture("spring-repair");
        stubDegradedGenerationThenPartialRepair();

        var created = commands.create(fixture.project(), request("Spring Repair"), fixture.user());
        var degraded = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY_WITH_ISSUES));
        TaskPlanVersionRecord degradedVersion = repository.requireVersion(
                fixture.project(), created.id(), degraded.latestVersionId());
        TaskPlanDraft beforeRepair = repository.draft(degradedVersion);
        var persistedIssues = issues.findPersistedByVersion(created.id(), degraded.latestVersionId());
        assertThat(persistedIssues).extracting(item -> item.issue().code())
                .contains("DEPENDENCY_DATE_CONFLICT");

        UUID issueId = persistedIssues.stream()
                .filter(item -> item.issue().code().equals("DEPENDENCY_DATE_CONFLICT"))
                .findFirst().orElseThrow().id();
        jdbc.update("update ai_task_plan set last_error_code='PLAN_VALIDATION_FAILED',last_error_summary='REPAIR / PLAN_VALIDATION_FAILED' where id=?", created.id());
        commands.partialRegenerate(fixture.project(), created.id(), new PartialRegenerateRequest(
                degraded.latestVersionId(), degraded.latestVersionNo(),
                List.of("t2"), Set.of("startDate"), Set.of(
                        "tempKey", "milestoneTempKey", "title", "objective", "sortOrder"),
                List.of(issueId), PartialRegenerateRequest.REPAIR_DATES_AND_DEPENDENCIES), fixture.user());

        var repaired = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        assertThat(repaired.lastErrorCode()).isNull();
        assertThat(repaired.lastErrorSummary()).isNull();
        TaskPlanVersionRecord repairedVersion = repository.requireVersion(
                fixture.project(), created.id(), repaired.latestVersionId());
        TaskPlanDraft afterRepair = repository.draft(repairedVersion);
        assertThat(repairedVersion.sourceType()).isEqualTo("AI_PARTIAL_REPAIR");
        assertThat(task(afterRepair, "t1")).isEqualTo(task(beforeRepair, "t1"));
        assertThat(task(afterRepair, "t2").startDate()).isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(task(afterRepair, "t2").title()).isEqualTo(task(beforeRepair, "t2").title());

        TaskPlanDraft conflictAgain = replaceTask(afterRepair,
                copyWithStartDate(task(afterRepair, "t2"), LocalDate.of(2026, 8, 10)));
        // The production command accepts a full editable Draft and returns to READY_WITH_ISSUES.
        UUID edited = commands.save(fixture.project(), created.id(),
                saveRequest(repaired, conflictAgain), fixture.user());
        var editable = repository.require(fixture.project(), created.id());
        assertThat(edited).isEqualTo(editable.latestVersionId());
        assertThat(editable.status()).isEqualTo(TaskPlanStatus.READY_WITH_ISSUES);

        TaskPlanDraft resolved = replaceTask(conflictAgain,
                copyWithStartDate(task(conflictAgain, "t2"), LocalDate.of(2026, 8, 16)));
        commands.save(fixture.project(), created.id(), saveRequest(editable, resolved), fixture.user());
        assertThat(repository.require(fixture.project(), created.id()).status()).isEqualTo(TaskPlanStatus.READY);
    }

    @Test
    void switchingToAnUnauthorizedModelPreservesTheExistingDraftAndItsFailureCause() throws Exception {
        Fixture fixture = fixture("spring-model-switch");
        stubLegalGeneration();
        var created = commands.create(fixture.project(), request("Model switch"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        var before = repository.requireVersion(fixture.project(), created.id(), ready.latestVersionId());
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenThrow(new BusinessException(com.shitulelv.aicollab.common.exception.ErrorCode.AI_MODEL_CREDENTIAL_INVALID));
        commands.regenerate(fixture.project(), created.id(), fixture.user());
        var failed = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.FAILED));
        assertThat(failed.latestVersionId()).isEqualTo(ready.latestVersionId());
        assertThat(failed.latestVersionNo()).isEqualTo(ready.latestVersionNo());
        assertThat(repository.requireVersion(fixture.project(), created.id(), failed.latestVersionId())).isEqualTo(before);
        assertThat(failed.lastErrorSummary()).contains("AI_MODEL_CREDENTIAL_INVALID").doesNotContain("安全校验");
    }

    @Test
    void productionTransactionRollsBackEventAndIssueFailures() throws Exception {
        Fixture fixture = fixture("spring-rollback");
        stubLegalGeneration();
        var created = commands.create(fixture.project(), request("Spring Rollback"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        TaskPlanDraft base = repository.draft(repository.requireVersion(
                fixture.project(), created.id(), ready.latestVersionId()));

        jdbc.execute("""
                create or replace function phase08_fail_event() returns trigger language plpgsql as $$
                begin raise exception 'injected event failure'; end $$;
                create trigger phase08_fail_event before insert on ai_task_plan_event
                for each row execute function phase08_fail_event()
                """);
        try {
            TaskPlanDraft changed = new TaskPlanDraft(base.summary() + " changed", base.assumptions(),
                    base.risks(), base.milestones(), base.tasks(), base.sources());
            assertThatThrownBy(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, changed), fixture.user())).isInstanceOf(RuntimeException.class);
            assertThat(repository.require(fixture.project(), created.id()).latestVersionId())
                    .isEqualTo(ready.latestVersionId());
            assertThat(repository.versions(fixture.project(), created.id())).hasSize(2);
        } finally {
            jdbc.execute("drop trigger if exists phase08_fail_event on ai_task_plan_event");
            jdbc.execute("drop function if exists phase08_fail_event()");
        }

        jdbc.execute("""
                create or replace function phase08_fail_issue() returns trigger language plpgsql as $$
                begin raise exception 'injected issue failure'; end $$;
                create trigger phase08_fail_issue before insert on ai_task_plan_validation_issue
                for each row execute function phase08_fail_issue()
                """);
        try {
            PlanTask t2 = task(base, "t2");
            TaskPlanDraft conflicting = replaceTask(base,
                    copyWithStartDate(t2, LocalDate.of(2026, 8, 10)));
            assertThatThrownBy(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, conflicting), fixture.user())).isInstanceOf(RuntimeException.class);
            assertThat(repository.require(fixture.project(), created.id()).latestVersionId())
                    .isEqualTo(ready.latestVersionId());
            assertThat(repository.versions(fixture.project(), created.id())).hasSize(2);
        } finally {
            jdbc.execute("drop trigger if exists phase08_fail_issue on ai_task_plan_validation_issue");
            jdbc.execute("drop function if exists phase08_fail_issue()");
        }
    }

    @Test
    void productionConcurrentEditsYieldOneSuccessAndOneConflict() throws Exception {
        Fixture fixture = fixture("spring-concurrent");
        stubLegalGeneration();
        var created = commands.create(fixture.project(), request("Spring Concurrent"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        TaskPlanDraft base = repository.draft(repository.requireVersion(
                fixture.project(), created.id(), ready.latestVersionId()));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, withSummary(base, "并发版本 A")), fixture.user()));
            var second = executor.submit(() -> commands.save(fixture.project(), created.id(),
                    saveRequest(ready, withSummary(base, "并发版本 B")), fixture.user()));
            int successes = 0;
            int conflicts = 0;
            for (var future : List.of(first, second)) {
                try {
                    future.get(15, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof BusinessException business
                            && business.getErrorCode() == ErrorCode.PLAN_VERSION_CONFLICT) {
                        conflicts++;
                    } else {
                        throw failure;
                    }
                }
            }
            assertThat(successes).isOne();
            assertThat(conflicts).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private void stubLegalGeneration() {
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(result(skeleton()));
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any(), any()))
                .thenReturn(result(legalDetail()));
    }

    @Test
    void rejectedRepairPersistsSafeDiagnosticsAndKeepsTheSeenVersion() throws Exception {
        Fixture fixture = fixture("repair-diagnostics");
        stubLegalGeneration();
        var created = commands.create(fixture.project(), request("Located repair"), fixture.user());
        var ready = awaitStatus(fixture.project(), created.id(), Set.of(TaskPlanStatus.READY));
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any(), any()))
                .thenReturn(result("{\"milestonePatches\":[],\"taskPatches\":[{\"tempKey\":\"t2\",\"description\":\"not allowed\"}]}"));
        commands.partialRegenerate(fixture.project(), created.id(), new PartialRegenerateRequest(
                ready.latestVersionId(), ready.latestVersionNo(), List.of("t2"), Set.of("startDate"),
                Set.of("description"), List.of(), PartialRegenerateRequest.RESCHEDULE_UNLOCKED_TASKS), fixture.user());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (repository.require(fixture.project(), created.id()).lastErrorCode() == null && System.nanoTime() < deadline) Thread.sleep(25);
        var failed = repository.require(fixture.project(), created.id());
        assertThat(failed.latestVersionId()).isEqualTo(ready.latestVersionId());
        var diagnostics = queries.detail(fixture.project(), created.id(), fixture.user()).repairDiagnostics();
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.getFirst().targetTempKey()).isEqualTo("t2");
        assertThat(diagnostics.getFirst().field()).isEqualTo("description");
        assertThat(diagnostics.getFirst().code()).isEqualTo("PATCH_FIELD_LOCKED");
        assertThat(diagnostics.getFirst().safeDetails()).isEmpty();
    }

    private void stubDegradedGenerationThenPartialRepair() {
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_SKELETON"), any(), any(), any(), any()))
                .thenReturn(result(skeleton()));
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_DETAIL"), any(), any(), any(), any()))
                .thenReturn(result(conflictingDetail()));
        when(model.generate(anyString(), anyString(), eq("TASK_PLAN_REPAIR_PATCH"), any(), any(), any(), any()))
                .thenReturn(result("{\"milestonePatches\":[],\"taskPatches\":[]}"),
                        result("{\"milestonePatches\":[],\"taskPatches\":[{\"tempKey\":\"t2\",\"startDate\":\"2026-08-16\"}]}"));
    }

    private static GenerationResult result(String content) {
        return new GenerationResult(content, "test", "deterministic", 1, 1, 1);
    }

    private static String skeleton() {
        return """
                {"summary":"规划摘要","assumptions":[],"risks":[],
                "milestones":[{"tempKey":"m1","title":"发布","objective":"完成发布","targetDate":null,"sortOrder":0}],
                "tasks":[
                  {"tempKey":"t1","milestoneTempKey":"m1","title":"基础任务","objective":"完成基础","sortOrder":0},
                  {"tempKey":"t2","milestoneTempKey":"m1","title":"后续任务","objective":"完成后续","sortOrder":1}
                ]}
                """;
    }

    private static String legalDetail() {
        return """
                {"milestones":[{"tempKey":"m1","description":"发布说明","sourceRefs":[]}],
                "tasks":[
                  {"tempKey":"t1","description":"基础说明","priority":"HIGH","estimatedHours":8,
                   "startDate":"2026-08-10","dueDate":"2026-08-15","suggestedAssigneeId":null,
                   "dependencyTempKeys":[],"sourceRefs":[]},
                  {"tempKey":"t2","description":"后续说明","priority":"MEDIUM","estimatedHours":4,
                   "startDate":"2026-08-16","dueDate":"2026-08-20","suggestedAssigneeId":null,
                   "dependencyTempKeys":["t1"],"sourceRefs":[]}
                ]}
                """;
    }

    private static String conflictingDetail() {
        return legalDetail().replace("\"startDate\":\"2026-08-16\"", "\"startDate\":\"2026-08-10\"");
    }

    private Fixture fixture(String prefix) {
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("insert into app_user(id,username,password_hash,display_name) values (?,?,?,?)",
                user, prefix + "-" + user.toString().substring(0, 8), "test-only-hash", "Spring 用户");
        jdbc.update("""
                insert into project(id,name,owner_id,created_by,start_date,due_date)
                values (?,?,?,?,date '2026-08-01',date '2026-08-31')
                """, project, "Spring 生产接线", user, user);
        jdbc.update("insert into project_member(project_id,user_id,role) values (?,?,'OWNER')",
                project, user);
        return new Fixture(user, project);
    }

    private static CreateTaskPlanRequest request(String title) {
        return new CreateTaskPlanRequest(title, "完成系统", "",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 20, List.of());
    }

    private com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord awaitStatus(
            UUID project, UUID plan, Set<TaskPlanStatus> expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord current;
        do {
            current = repository.require(project, plan);
            if (expected.contains(current.status())) return current;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("规划未进入预期状态，当前状态：" + current.status());
    }

    private static SaveTaskPlanVersionRequest saveRequest(
            com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord plan, TaskPlanDraft draft) {
        return new SaveTaskPlanVersionRequest(plan.latestVersionId(), plan.latestVersionNo(),
                draft, null);
    }

    private static TaskPlanDraft withSummary(TaskPlanDraft draft, String summary) {
        return new TaskPlanDraft(summary, draft.assumptions(), draft.risks(),
                draft.milestones(), draft.tasks(), draft.sources());
    }

    private static PlanTask task(TaskPlanDraft draft, String tempKey) {
        return draft.tasks().stream().filter(item -> item.tempKey().equals(tempKey))
                .findFirst().orElseThrow();
    }

    private static TaskPlanDraft replaceTask(TaskPlanDraft draft, PlanTask replacement) {
        return new TaskPlanDraft(draft.summary(), draft.assumptions(), draft.risks(),
                draft.milestones(), draft.tasks().stream()
                .map(item -> item.tempKey().equals(replacement.tempKey()) ? replacement : item).toList(),
                draft.sources());
    }

    private static PlanTask copyWithStartDate(PlanTask task, LocalDate startDate) {
        return new PlanTask(task.tempKey(), task.milestoneTempKey(), task.title(), task.objective(),
                task.description(), task.priority(), task.estimatedHours(), startDate, task.dueDate(),
                task.suggestedAssigneeId(), task.assigneeId(), task.dependencyTempKeys(),
                task.sourceRefs(), task.sortOrder());
    }

    private record Fixture(UUID user, UUID project) {
    }
}
