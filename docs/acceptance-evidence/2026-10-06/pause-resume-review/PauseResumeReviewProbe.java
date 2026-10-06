package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.application.AgentRunService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.transaction.support.TransactionTemplate;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Standalone review evidence; uses only its own Testcontainers database and fake model. */
public class PauseResumeReviewProbe {
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
            var test = new AgentPauseResumePostgresTest();
            test.setUp();
            var repo = AgentPauseResumePostgresTest.repository;
            var jdbc = AgentPauseResumePostgresTest.jdbc;
            var json = AgentPauseResumePostgresTest.json;
            var tx = new TransactionTemplate(AgentPauseResumePostgresTest.txManager);
            Object f = invoke(test, "fixture", "核对项目风险");
            UUID project = id(f, "project"), session = id(f, "session"), user = id(f, "user");
            AgentRunView run = repo.createRun(project, session, user, "核对项目风险", false, null, null);
            tx.execute(s -> repo.requestPause(project, run.id()));
            tx.execute(s -> repo.requestResume(project, run.id()));
            // A delayed/repeated resume input arrives after the original run has finished.
            jdbc.update("UPDATE agent_run SET status='SUCCEEDED',finished_at=now() WHERE id=?", run.id());
            var service = new AgentRunService(mock(ProjectAccessGuard.class), repo,
                    new AgentSkillRegistry(), json, mock(AgentEventService.class),
                    mock(AgentEventRepository.class), mock(AgentApprovalRepository.class));
            String revisionBefore = jdbc.queryForObject(
                    "SELECT working_state->>'goalRevision' FROM agent_session WHERE id=?", String.class, session);
            AgentRunView returned = tx.execute(s -> service.submit(project, session, user,
                    new SubmitAgentMessageRequest("继续", null, null, run.id().toString())));
            Long count = jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?", Long.class, session);
            String revisionAfter = jdbc.queryForObject(
                    "SELECT working_state->>'goalRevision' FROM agent_session WHERE id=?", String.class, session);
            boolean recreated = returned != null && !returned.id().equals(run.id()) && count == 2;
            System.out.println("PROBE_STALE_RESUME newRun=" + recreated + " runs=" + count
                    + " goalRevision=" + revisionBefore + "->" + revisionAfter
                    + " newGoal=" + (returned == null ? "null" : returned.goal()));
            if (!recreated) throw new AssertionError("Stale resume was not reproduced");

            Object sf = invoke(test, "fixture", "继续核对原目标");
            UUID sp = id(sf, "project"), ss = id(sf, "session"), su = id(sf, "user");
            AgentRunView queued = repo.createRun(sp, ss, su, "继续核对原目标", false, null, null);
            // Prevent the preceding newly created run being selected for this claim.
            jdbc.update("UPDATE agent_run SET status='SUCCEEDED' WHERE session_id=?", session);
            repo.claimNext("summary-probe", java.time.OffsetDateTime.now(), Duration.ofMinutes(6)).orElseThrow();
            AgentRunView summaryRun = repo.findRun(sp, queued.id()).orElseThrow();
            var fake = mock(RoutingAgentModelExecutor.class);
            var calls = new AtomicInteger();
            when(fake.callModelWithoutTools(any(), any())).thenAnswer(inv -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    tx.execute(s -> repo.requestPause(sp, summaryRun.id()));
                    return new ModelTurnResult("仍有效约束。".repeat(300), List.of(),
                            ModelFinishReason.STOP, null, "fake", "fake", 1L);
                }
                System.out.println("PROBE_RECOMPRESS pauseAlreadyCommitted=" + repo.isPauseRequested(sp, summaryRun.id()));
                return new ModelTurnResult("仍有效约束：保留原目标。", List.of(),
                        ModelFinishReason.STOP, null, "fake", "fake", 1L);
            });
            var candidates = repo.listMessages(sp, ss, 10);
            var composition = new AgentModelMessageComposer.Composition(List.of(),
                    AgentModelMessageComposer.CompositionStats.empty(), null, candidates);
            new AgentContextSummarizer(repo, fake, json).maybeSummarize(summaryRun, composition, 100_000);
            System.out.println("PROBE_SUMMARY modelRequests=" + calls.get()
                    + " requested=" + repo.isPauseRequested(sp, summaryRun.id()));
            if (calls.get() != 2) throw new AssertionError("Recompression admission defect was not reproduced");
            System.out.println("REVIEW_BACKEND_PROBES_CONFIRMED=2");
        } finally {
            pg.stop();
        }
    }
}
