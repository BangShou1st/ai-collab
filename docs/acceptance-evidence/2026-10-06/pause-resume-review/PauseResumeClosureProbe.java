package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.AgentRunService;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.transaction.support.TransactionTemplate;
import java.lang.reflect.Method;
import java.util.UUID;
import static org.mockito.Mockito.mock;

/** Focused follow-up for the same F1 control-scope contract, isolated PostgreSQL only. */
public class PauseResumeClosureProbe {
    static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    static UUID id(Object fixture, String name) throws Exception {
        return (UUID) invoke(fixture, name);
    }
    public static void main(String[] args) throws Exception {
        var pg = AgentPauseResumePostgresTest.POSTGRES;
        pg.start();
        try {
            AgentPauseResumePostgresTest.migrate();
            var t = new AgentPauseResumePostgresTest();
            t.setUp();
            var repo = AgentPauseResumePostgresTest.repository;
            var jdbc = AgentPauseResumePostgresTest.jdbc;
            var tx = new TransactionTemplate(AgentPauseResumePostgresTest.txManager);
            var service = new AgentRunService(mock(ProjectAccessGuard.class), repo,
                    new AgentSkillRegistry(), AgentPauseResumePostgresTest.json,
                    mock(AgentEventService.class), mock(AgentEventRepository.class),
                    mock(AgentApprovalRepository.class));
            for (String status : new String[] {"FAILED_RETRYABLE", "WAITING_FOR_USER_INPUT"}) {
                Object f = invoke(t, "fixture", "Original goal");
                UUID project = id(f, "project"), session = id(f, "session"), user = id(f, "user");
                var run = repo.createRun(project, session, user, "Original goal", false, null, null);
                tx.execute(s -> repo.requestPause(project, run.id()));
                tx.execute(s -> repo.requestResume(project, run.id()));
                // The resumed worker has progressed before a delayed, bound input arrives.
                jdbc.update("UPDATE agent_run SET status=? WHERE id=?", status, run.id());
                var returned = tx.execute(s -> service.submit(project, session, user,
                        new SubmitAgentMessageRequest("继续", null, null, run.id().toString())));
                Long count = jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?", Long.class, session);
                boolean newRun = returned != null && !returned.id().equals(run.id()) && count == 2;
                System.out.println("PROBE_BOUND_INPUT status=" + status + " newRun=" + newRun + " runs=" + count);
                if (!newRun) throw new AssertionError("Expected control-scope defect not reproduced: " + status);
            }
            System.out.println("F1_NONTERMINAL_SCOPE_PROBE_CONFIRMED=2");
        } finally {
            pg.stop();
        }
    }
}
