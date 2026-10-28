package com.shitulelv.aicollab.agent.infrastructure.diagnostics;
import com.shitulelv.aicollab.agent.application.AgentWorkerOutcome;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolExecutionSafety;
import com.shitulelv.aicollab.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
/** Process events reset on restart; committed persistence events never count rollbacks. */
@Component
public class AgentRuntimeMetrics {
    private final MeterRegistry registry;
    public AgentRuntimeMetrics(MeterRegistry registry) { this.registry=registry; }
    private void pair(String counter,String timer,long nanos,String... tags) {
        try {
            registry.counter(counter,tags).increment();
            if (nanos >= 0) registry.timer(timer,tags).record(nanos,TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) { /* Metrics cannot change business outcomes. */ }
    }
    public void worker(AgentWorkerOutcome result,long nanos) {
        String outcome=result==null ? "unexpected_error" : switch(result.status()) {
            case SUCCEEDED -> "success";
            case FAILED -> "failed";
            case FAILED_RETRYABLE -> "failed_retryable";
            case CANCELED -> "canceled";
            case PAUSED -> "paused";
            case BUDGET_EXCEEDED -> "budget_exceeded";
            case WAITING_FOR_APPROVAL -> "waiting_for_approval";
            case WAITING_FOR_USER_INPUT -> "waiting_for_user_input";
            case QUEUED -> "queued";
            default -> "unexpected_error";
        };
        pair("agent.worker.attempt","agent.worker.attempt.duration",nanos,"outcome",outcome);
    }
    public void dispatchRejected() {
        try { registry.counter("agent.worker.dispatch.rejected","reason","executor_full").increment(); }
        catch (RuntimeException ignored) { }
    }
    public <T> T model(String purpose,Supplier<T> call) {
        try (var observation = new com.shitulelv.aicollab.infrastructure.ai.model.AiOutboundObservation()) {
            String outcome = "success";
            try { return call.get(); }
            catch (RuntimeException | Error failure) {
                outcome = Thread.currentThread().isInterrupted() ? "interrupted" : modelOutcome(failure);
                throw failure;
            } finally {
                if (observation.wasStarted()) pair("agent.model.request", "agent.model.request.duration",
                        observation.elapsed(), "purpose", "summary".equals(purpose) ? "summary" : "turn", "outcome", outcome);
            }
        }
    }
    static String modelOutcome(Throwable failure) {
        if(failure instanceof com.shitulelv.aicollab.agent.application.runtime.LegacyDecisionParseFailure) return "invalid_response";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException) return "interrupted";
            if(cause instanceof java.net.SocketTimeoutException || cause instanceof java.util.concurrent.TimeoutException) return "timeout";
            if(cause instanceof BusinessException business) return switch(business.getErrorCode()) {
                case AI_MODEL_TIMEOUT -> "timeout";
                case AI_PROVIDER_INVALID_RESPONSE,AI_PROVIDER_OUTPUT_TRUNCATED -> "invalid_response";
                case AI_PROVIDER_ERROR,AI_MODEL_CREDENTIAL_INVALID,AI_PROVIDER_QUOTA_EXCEEDED,AI_PROVIDER_NOT_FOUND,AI_PROVIDER_REQUEST_REJECTED -> "provider_error";
                case AGENT_RUN_CANCELED -> "interrupted";
                default -> "other";
            };
        }
        return "other";
    }
    public void invocation(String safety,String status,long nanos) {
        String bounded;
        try { bounded=AgentToolExecutionSafety.valueOf(safety).name(); }
        catch(RuntimeException invalid) { bounded=AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT.name(); }
        String outcome=switch(status) {
            case "SUCCEEDED","FAILED","REJECTED","CANCELED","SKIPPED","UNKNOWN" -> status.toLowerCase(Locale.ROOT);
            default -> null;
        };
        if(outcome==null) return;
        String fixed=bounded;
        long recordedAt=System.nanoTime();
        Runnable record=()->pair("agent.tool.invocation.outcome","agent.tool.invocation.duration",nanos < 0 ? -1 : nanos+System.nanoTime()-recordedAt,"safety",fixed,"outcome",outcome);
        if(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { record.run(); }
            });
        } else record.run();
    }
}
