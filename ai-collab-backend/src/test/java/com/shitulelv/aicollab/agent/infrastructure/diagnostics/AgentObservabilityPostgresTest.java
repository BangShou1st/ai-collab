package com.shitulelv.aicollab.agent.infrastructure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolExecutionSafety;
import com.shitulelv.aicollab.agent.infrastructure.repository.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentObservabilityPostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static AgentRepository repository;
    static AgentRunEventRecorder recorder;
    static final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    static final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    static final AgentRuntimeMetrics metrics=new AgentRuntimeMetrics(registry);
    static UUID project=UUID.randomUUID(), user=UUID.randomUUID();
    static String before;
    @BeforeAll static void setup() {
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).target("67").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        recorder=new AgentRunEventRecorder(jdbc,json);
        ReflectionTestUtils.setField(recorder,"metrics",metrics);
        repository=new AgentRepository(jdbc,json,recorder);
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,'observe','test-hash','Observe')",user);
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'Observe',?,?)",project,user,user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",project,user);
        var run=run();
        jdbc.update("""
            INSERT INTO agent_tool_invocation(run_id,turn_sequence,ordinal,invocation_id,tool_call_id,tool_name,arguments_json,status,dispatch_started_at,dispatch_claim_version,execution_safety,updated_at)
            SELECT ?,2,n,gen_random_uuid(),n::text,'fixture','{}'::jsonb,
                CASE WHEN n<=10 THEN 'UNKNOWN' WHEN n=11 THEN 'PENDING' ELSE 'SUCCEEDED' END,
                CASE WHEN n=1 THEN now()-interval '10 minutes' ELSE NULL END,
                CASE WHEN n=1 THEN 1 ELSE NULL END,CASE WHEN n=1 THEN 'UNCONFIRMABLE_SIDE_EFFECT' ELSE NULL END,
                now()-interval '5 minutes' FROM generate_series(1,30000) n
            """,run.id());
        before=jdbc.queryForObject("SELECT md5(jsonb_agg(to_jsonb(i) ORDER BY ordinal)::text) FROM agent_tool_invocation i",String.class);
        Flyway.configure().dataSource(ds).load().migrate();
    }
    static AgentRunView run() {
        var session=repository.createSession(project,user,"Observe");
        var run=repository.createRun(project,session.id(),user,"Observe",false,"ITERATION_PLANNING",null);
        jdbc.update("UPDATE agent_run SET status='RUNNING',lease_expires_at=now()-interval '1 minute',claim_version=1 WHERE id=?",run.id());
        return repository.findRun(project,run.id()).orElseThrow();
    }
    @Test @Order(1) void i01UpgradePreservesEveryExistingRowAndUsesPartialIndex() throws Exception {
        assertThat(jdbc.queryForObject("SELECT md5(jsonb_agg(to_jsonb(i) ORDER BY ordinal)::text) FROM agent_tool_invocation i",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='idx_agent_tool_invocation_unknown_age'",String.class))
                .contains("COALESCE(dispatch_started_at, updated_at)","UNKNOWN");
        jdbc.execute("ANALYZE agent_tool_invocation");
        String plan=String.join("\n",jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) SELECT count(*),min(COALESCE(dispatch_started_at,updated_at)) FROM agent_tool_invocation WHERE status='UNKNOWN'",String.class));
        assertThat(plan).contains("idx_agent_tool_invocation_unknown_age");
        Files.writeString(Path.of("target/p6-observability-explain.txt"),plan);
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(71);
    }
    @Test @Order(2) void i02i03SnapshotMatchesDurableFactsAndLeaseRecovery() {
        var state=new AgentRuntimeStateMetrics(new AgentRuntimeStateRepository(jdbc),new SimpleMeterRegistry());
        var snapshot=new AgentRuntimeStateRepository(jdbc).read();
        assertThat(snapshot.unknown()).isEqualTo(10);
        assertThat(snapshot.oldest()).isBefore(java.time.Instant.now().minusSeconds(599));
        assertThat(snapshot.counts().get("RUNNING")).isEqualTo(1);
        assertThat(snapshot.expired()).isEqualTo(1);
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()+interval '6 minutes' WHERE project_id=?",project);
        assertThat(new AgentRuntimeStateRepository(jdbc).read().expired()).isZero();
        assertThat(jdbc.queryForObject("SELECT claim_version FROM agent_run WHERE project_id=?",Integer.class,project)).isEqualTo(1);
        state.refresh();
    }
    @Test @Order(3) void i04RollbackNeverCountsAndCommittedReplayCannotCountAgain() {
        for(String status:List.of("SUCCEEDED","FAILED","REJECTED","CANCELED","SKIPPED","UNKNOWN")) {
            var run=run();
            var call=new ModelToolCall("call","fixture",json.createObjectNode());
            var original=run;
            run=tx.execute(s->repository.recordModelTurn(original,new ModelTurnResult("",List.of(call),ModelFinishReason.TOOL_CALLS,null,"FAKE","fixture",1L)));
            var current=run;
            var input=json.createObjectNode().put("toolCallId",call.id()).set("arguments",call.arguments());
            var output=json.createObjectNode().put("status",status);
            double baseline=total();
            try(var scope=new AgentDiagnosticScope(run.id(),1,null)) {
                AgentDiagnosticScope.submitted(call.id());
                tx.executeWithoutResult(s->{
                    repository.recordToolResult(current,call.name(),input,output,!status.equals("SUCCEEDED"));
                    assertThat(total()).isEqualTo(baseline);
                    s.setRollbackOnly();
                });
                assertThat(total()).isEqualTo(baseline);
                assertThat(jdbc.queryForObject("SELECT status FROM agent_tool_invocation WHERE invocation_id=?",String.class,repository.invocationId(run,call))).isEqualTo("PENDING");
                tx.executeWithoutResult(s->repository.recordToolResult(current,call.name(),input,output,!status.equals("SUCCEEDED")));
                assertThat(total()).isEqualTo(baseline+1);
                assertThat(registry.find("agent.tool.invocation.duration").timers().stream()
                        .mapToLong(io.micrometer.core.instrument.Timer::count).sum()).isEqualTo((long)baseline+1);
                assertThat(repository.knownInvocationResult(current,call)).isPresent();
                assertThatThrownBy(()->tx.executeWithoutResult(s->repository.recordToolResult(current,call.name(),input,output,!status.equals("SUCCEEDED"))))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(total()).isEqualTo(baseline+1);
            }
        }
    }
    static double total() {
        return registry.find("agent.tool.invocation.outcome").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }
    @Test @Order(4) void i04BatchCleanupUnknownAndCanceledCommitExactlyOnce() {
        var original=run();
        var safe=new ModelToolCall("safe","fixture",json.createObjectNode());
        var unsafe=new ModelToolCall("unsafe","fixture",json.createObjectNode());
        var run=tx.execute(s->repository.recordModelTurn(original,new ModelTurnResult("",List.of(safe,unsafe),ModelFinishReason.TOOL_CALLS,null,"FAKE","fixture",1L)));
        jdbc.update("UPDATE agent_run SET lease_expires_at=now()+interval '6 minutes' WHERE id=?",run.id());
        tx.executeWithoutResult(s->repository.recordInvocationDispatch(run,unsafe,AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT));
        double baseline=total();
        tx.executeWithoutResult(s->{repository.recordCanceled(run);s.setRollbackOnly();});
        assertThat(total()).isEqualTo(baseline);
        tx.executeWithoutResult(s->repository.recordCanceled(run));
        assertThat(total()).isEqualTo(baseline+2);
        assertThat(repository.knownInvocationResult(run,unsafe).orElseThrow().path("status").asText()).isEqualTo("UNKNOWN");
        tx.executeWithoutResult(s->repository.recordCanceled(run));
        assertThat(total()).isEqualTo(baseline+2);
    }
}
