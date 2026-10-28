package com.shitulelv.aicollab.agent.infrastructure.diagnostics;

import com.shitulelv.aicollab.agent.application.*;
import com.shitulelv.aicollab.agent.application.runtime.*;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.*;
import com.shitulelv.aicollab.common.exception.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.task.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final AgentRuntimeMetrics metrics = new AgentRuntimeMetrics(registry);
    @AfterEach void clean() { MDC.clear(); registry.close(); }

    static void outbound() { com.shitulelv.aicollab.infrastructure.ai.model.AiOutboundObservation.markStarted(com.shitulelv.aicollab.infrastructure.ai.model.AiOutboundObservation.current()); }

    @Test void c01MetricBackendFailureNeverChangesBusinessResults() {
        var broken=mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(broken.counter(anyString(),any(String[].class))).thenThrow(new IllegalStateException("metrics unavailable"));
        var isolated=new AgentRuntimeMetrics(broken);
        assertThatCode(()->isolated.worker(null,1)).doesNotThrowAnyException();
        assertThatCode(isolated::dispatchRejected).doesNotThrowAnyException();
        assertThatCode(()->isolated.invocation("SAFE_READ","SUCCEEDED",1)).doesNotThrowAnyException();
        assertThat(isolated.model("turn",()->{outbound();return "result";})).isEqualTo("result");
    }

    @Test void c01c04FixedInvocationOutcomesAndPrivateTags() {
        for (String safety : List.of("SAFE_READ","IDEMPOTENT_OR_RECONCILABLE","UNCONFIRMABLE_SIDE_EFFECT"))
            for(String status:List.of("SUCCEEDED","FAILED","REJECTED","CANCELED","SKIPPED","UNKNOWN")) {
                metrics.invocation(safety,status,100);
                var tags = new String[]{"safety",safety,"outcome",status.toLowerCase(Locale.ROOT)};
                assertThat(registry.get("agent.tool.invocation.outcome").tags(tags).counter().count()).isEqualTo(1);
                assertThat(registry.get("agent.tool.invocation.duration").tags(tags).timer().count()).isEqualTo(1);
            }
        assertThat(registry.getMeters()).hasSize(36);
        registry.getMeters().forEach(m -> assertThat(m.getId().getTags()).allSatisfy(t ->
                assertThat(t.getKey()).isIn("safety","outcome")));
    }
    @Test void c02AttemptAllReturnOutcomesAndUnexpectedExit() {
        for(var status:AgentRunStatus.values()) metrics.worker(new AgentWorkerOutcome(status,null,null,"secret"),200);
        metrics.worker(null,200);
        assertThat(registry.getMeters().stream().filter(m->m.getId().getName().equals("agent.worker.attempt"))
                .mapToDouble(m->((io.micrometer.core.instrument.Counter)m).count()).sum()).isEqualTo(12);
        assertThat(registry.get("agent.worker.attempt").tag("outcome","unexpected_error").counter().count()).isEqualTo(3);
        assertThat(registry.get("agent.worker.attempt.duration").tag("outcome","unexpected_error").timer().count()).isEqualTo(3);
    }
    @Test void c03ModelSuccessAndBoundedFailureClassification() {
        assertThat(metrics.model("turn",()->{ outbound(); return "ok"; })).isEqualTo("ok");
        assertThatThrownBy(() -> metrics.model("turn", () -> { throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE); }))
                .isInstanceOf(BusinessException.class);
        assertThat(registry.get("agent.model.request").tags("purpose","turn","outcome","success").counter().count()).isEqualTo(1);
        Map<ErrorCode,String> failures=Map.of(ErrorCode.AI_MODEL_TIMEOUT,"timeout",
                ErrorCode.AI_PROVIDER_ERROR,"provider_error",ErrorCode.AI_PROVIDER_INVALID_RESPONSE,"invalid_response",
                ErrorCode.AGENT_RUN_CANCELED,"interrupted");
        failures.forEach((code,outcome)->{
            var failure=new BusinessException(code,"private prompt/api key");
            assertThatThrownBy(()->metrics.model("summary",()->{outbound();throw failure;})).isSameAs(failure);
            assertThat(registry.get("agent.model.request").tags("purpose","summary","outcome",outcome).counter().count()).isEqualTo(1);
            assertThat(registry.get("agent.model.request.duration").tags("purpose","summary","outcome",outcome).timer().count()).isEqualTo(1);
        });
        assertThat(AgentRuntimeMetrics.modelOutcome(new RuntimeException(new InterruptedException()))).isEqualTo("interrupted");
        assertThat(AgentRuntimeMetrics.modelOutcome(new IllegalStateException("secret"))).isEqualTo("other");
        assertThat(registry.getMeters().toString()).doesNotContain("private prompt","api key");
    }
    @Test void c06SnapshotFailureNeverFakesZeroAndScrapeNeverQueriesDatabase() {
        var repository=mock(AgentRuntimeStateRepository.class);
        var state=new AgentRuntimeStateMetrics(repository,registry);
        assertThat(registry.get("agent.invocation.unknown.count").gauge().value()).isNaN();
        when(repository.read()).thenThrow(new IllegalStateException("secret"));
        state.refresh();
        assertThat(registry.get("agent.diagnostics.snapshot.age").gauge().value()).isNaN();
        doReturn(new AgentRuntimeStateRepository.Snapshot(Map.of("RUNNING",2L),1,3,
                java.time.Instant.now().minusSeconds(120),java.time.Instant.now().minusSeconds(10))).when(repository).read();
        state.refresh();
        double age=registry.get("agent.diagnostics.snapshot.age").gauge().value();
        when(repository.read()).thenThrow(new IllegalStateException("secret"));
        state.refresh();
        assertThat(registry.get("agent.invocation.unknown.count").gauge().value()).isEqualTo(3);
        assertThat(registry.get("agent.run.lease.expired.count").gauge().value()).isEqualTo(1);
        assertThat(registry.get("agent.run.status.count").tag("status","RUNNING").gauge().value()).isEqualTo(2);
        assertThat(registry.get("agent.run.status.count").tag("status","CREATED").gauge().value()).isZero();
        assertThat(registry.get("agent.diagnostics.snapshot.age").gauge().value()).isGreaterThanOrEqualTo(age);
        assertThat(registry.get("agent.invocation.unknown.oldest.age").gauge().value()).isGreaterThanOrEqualTo(120);
        assertThat(registry.get("agent.diagnostics.snapshot.refresh.failures").counter().count()).isEqualTo(2);
        verify(repository,times(3)).read();
    }
    @Test void c07ThreadReuseAndNestedScopeRestoreOnExceptionAndCancellation() throws Exception {
        try(var pool=Executors.newSingleThreadExecutor()) {
            for(int i=0;i<3;i++) {
                final int path=i;
                pool.submit(()->{
                    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                    UUID run=UUID.randomUUID(), invocation=UUID.randomUUID();
                    try {
                        try(var scope=new AgentDiagnosticScope(run,4,null)) {
                            assertThat(MDC.get("agentRunId")).isEqualTo(run.toString());
                            try(var tool=new AgentDiagnosticScope(run,4,invocation)) {
                                assertThat(MDC.get("agentInvocationId")).isEqualTo(invocation.toString());
                                if(path==1) throw new IllegalStateException();
                                if(path==2) throw new CancellationException();
                            }
                            assertThat(MDC.get("agentInvocationId")).isNull();
                        }
                    } catch(IllegalStateException expected) { }
                    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                }).get(5,TimeUnit.SECONDS);
            }
        }
    }
    @Test void c02c07ActualWorkerExceptionCleansMdcAndCountsOnce() {
        var repository=mock(AgentRepository.class);
        var worker=new AgentWorker(repository,mock(AgentRuntimeCoordinator.class));
        ReflectionTestUtils.setField(worker,"metrics",metrics);
        var claim=new ClaimedAgentRun(UUID.randomUUID(),null,UUID.randomUUID(),null,null,null,0,"private",AgentRunStatus.QUEUED,false,false,4);
        when(repository.findRun(claim.projectId(),claim.id())).thenAnswer(call->{
            assertThat(MDC.get("agentRunId")).isEqualTo(claim.id().toString());
            throw new IllegalStateException("private");
        });
        assertThatThrownBy(()->worker.process(claim)).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(registry.get("agent.worker.attempt").tag("outcome","unexpected_error").counter().count()).isEqualTo(1);
    }
    @Test void c02c07WorkerReturnAndCancellationPathsPreserveCorrelation() {
        var repository=mock(AgentRepository.class);
        var coordinator=mock(AgentRuntimeCoordinator.class);
        var worker=new AgentWorker(repository,coordinator);
        ReflectionTestUtils.setField(worker,"metrics",metrics);
        var run=mock(AgentRunView.class);
        when(run.maxSteps()).thenReturn(16);
        var claim=new ClaimedAgentRun(UUID.randomUUID(),null,UUID.randomUUID(),null,null,null,0,"private",AgentRunStatus.QUEUED,false,false,7);
        when(repository.findRun(claim.projectId(),claim.id())).thenReturn(Optional.of(run));
        for(var status:List.of(AgentRunStatus.SUCCEEDED,AgentRunStatus.PAUSED,AgentRunStatus.FAILED_RETRYABLE)) {
            doAnswer(call->{
                assertThat(MDC.get("agentClaimVersion")).isEqualTo("7");
                return new AgentWorkerOutcome(status,null,null,null);
            }).when(coordinator).advance(run);
            assertThat(worker.process(claim).status()).isEqualTo(status);
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
        doThrow(new BusinessException(ErrorCode.AGENT_RUN_CANCELED)).when(coordinator).advance(run);
        assertThat(worker.process(claim).status()).isEqualTo(AgentRunStatus.CANCELED);
        verify(repository).recordCanceled(run);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(registry.get("agent.worker.attempt").tag("outcome","canceled").counter().count()).isEqualTo(1);
    }
    @Test void c08FailureLogsNeverIncludeSensitiveExceptionText() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(AgentRuntimeStateMetrics.class);
        var capture=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start();logger.addAppender(capture);
        try {
            var repository=mock(AgentRuntimeStateRepository.class);
            when(repository.read()).thenThrow(new IllegalStateException("secret-prompt api-key Authorization Cookie arguments_json result_json"));
            new AgentRuntimeStateMetrics(repository,registry).refresh();
            assertThat(capture.list).hasSize(1);
            assertThat(capture.list.getFirst().getFormattedMessage()).contains("IllegalStateException")
                    .doesNotContain("secret-prompt","api-key","Authorization","Cookie","arguments_json","result_json");
            assertThat(capture.list.getFirst().getThrowableProxy()).isNull();
        } finally { logger.detachAppender(capture);capture.stop(); }
    }
    @Test void c10RealExecutorSaturationButIdlePollDoesNotCount() throws Exception {
        var recovery=mock(AgentRecoveryJob.class);
        var executor=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(0);executor.initialize();
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            executor.execute(()->{started.countDown();try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var job=new AgentRuntimeJob(recovery,mock(AgentWorker.class),mock(AgentApprovalRepository.class),executor);
            ReflectionTestUtils.setField(job,"metrics",metrics);
            job.tick();job.tick();
            verifyNoInteractions(recovery);
            assertThat(registry.get("agent.worker.dispatch.rejected").counter().count()).isEqualTo(2);
            var idle=new AgentRuntimeJob(recovery,mock(AgentWorker.class),mock(AgentApprovalRepository.class),Runnable::run);
            ReflectionTestUtils.setField(idle,"metrics",metrics);
            when(recovery.claim(any(),any())).thenReturn(Optional.empty());
            idle.tick();
            assertThat(registry.get("agent.worker.dispatch.rejected").counter().count()).isEqualTo(2);
            assertThat(registry.find("agent.worker.attempt").counter()).isNull();
        } finally { release.countDown();executor.shutdown(); }
    }
}
