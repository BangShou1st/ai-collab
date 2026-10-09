package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.*;
import com.shitulelv.aicollab.agent.application.AgentPlanningOperationService;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Replays durable fault evidence against real isolated PostgreSQL; not model quality evidence. */
@EnabledIfEnvironmentVariable(named="AI_RELIABILITY_ASSERTIONS",matches="true")
class ReliabilityOperationDatabaseTest {
    private void check(String name,String status,String error) throws Exception {
        var jdbc=AcceptanceDatabaseSupport.jdbc();
        var json=new ObjectMapper();
        var state=json.readTree(Files.readString(Path.of("target/reliability-fault-state.json")));
        UUID project=UUID.fromString(state.path("projectId").asText());
        UUID operation=UUID.fromString(state.path("cases").path(name).path("operationId").asText());
        UUID user=jdbc.queryForObject("SELECT requester_id FROM agent_planning_operation WHERE id=?",UUID.class,operation);
        // HTTP acceptance separately exercises real authorization. Here SQL derivation is under test.
        var service=new AgentPlanningOperationService(jdbc,json,mock(ProjectAccessGuard.class),null,null,null);
        var result=service.get(project,operation,user);
        assertThat(result.path("status").asText()).isEqualTo(status);
        if(error==null)assertThat(result.path("errorCode").isNull()).isTrue();
        else assertThat(result.path("errorCode").asText()).isEqualTo(error);
        if(error!=null) {
            assertThat(result.path("versionNo").asInt()).isEqualTo(1);
            // Simulate an unobserved terminal transition, then roll back the injection.
            // A later retry shares generation_seq; it must not replace the first detail result.
            var tx=new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            tx.executeWithoutResult(transaction -> {
                jdbc.update("UPDATE agent_planning_operation SET status='ACCEPTED',result_version_id=null WHERE id=?",operation);
                var recovered=service.get(project,operation,user);
                assertThat(recovered.path("status").asText()).isEqualTo(status);
                assertThat(recovered.path("errorCode").asText()).isEqualTo(error);
                assertThat(recovered.path("versionNo").asInt()).isEqualTo(1);
                transaction.setRollbackOnly();
            });
        }
        var events=jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation_event WHERE operation_id=?",Integer.class,operation);
        assertThat(service.get(project,operation,user)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation_event WHERE operation_id=?",Integer.class,operation)).isEqualTo(events);
        assertThat(service.list(project,UUID.fromString(state.path("cases").path(name).path("sessionId").asText()),user))
                .anySatisfy(item -> assertThat(item).isEqualTo(result));
    }
    @Test void timeoutRemainsAttributedToOriginalGenerationAfterSuccessfulRetry()throws Exception {
        check("timeout-real","DETAIL_GENERATION_FAILED","PLANNING_MODEL_TIMEOUT");
    }
    @Test void lateCompletionAfterCancellationShowsCancellationRatherThanNewTarget()throws Exception {
        check("race-complete","CANCELED","PLAN_GENERATION_CANCELED");
    }
    @Test void queuedRestartRetainsSuccessfulOriginalOperation()throws Exception {
        check("queued-valid","READY",null);
    }
    @Test void runningRestartRetainsOriginalFailureAfterSuccessfulRetry()throws Exception {
        check("running","DETAIL_GENERATION_FAILED","PROCESS_RESTARTED");
    }
}
