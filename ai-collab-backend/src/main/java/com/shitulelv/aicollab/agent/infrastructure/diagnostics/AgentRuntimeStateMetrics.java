package com.shitulelv.aicollab.agent.infrastructure.diagnostics;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRuntimeStateRepository;
import io.micrometer.core.instrument.*;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
/** Scrapes read last-good memory only. NaN explicitly means no successful refresh yet. */
@Component
public class AgentRuntimeStateMetrics {
    private static final Logger log=LoggerFactory.getLogger(AgentRuntimeStateMetrics.class);
    private final AgentRuntimeStateRepository repository;
    private final MeterRegistry registry;
    private volatile AgentRuntimeStateRepository.Snapshot snapshot;
    private long lastWarning;
    private boolean failing;
    public AgentRuntimeStateMetrics(AgentRuntimeStateRepository repository,MeterRegistry registry) {
        this.repository=repository;this.registry=registry;
        for(var status:AgentRunStatus.values()) Gauge.builder("agent.run.status.count",this,m->m.count(status)).tag("status",status.name()).register(registry);
        Gauge.builder("agent.run.lease.expired.count",this,m->m.snapshot==null?Double.NaN:m.snapshot.expired()).register(registry);
        Gauge.builder("agent.invocation.unknown.count",this,m->m.snapshot==null?Double.NaN:m.snapshot.unknown()).register(registry);
        Gauge.builder("agent.invocation.unknown.oldest.age",this,AgentRuntimeStateMetrics::unknownAge).baseUnit("seconds").register(registry);
        Gauge.builder("agent.diagnostics.snapshot.age",this,AgentRuntimeStateMetrics::age).baseUnit("seconds").register(registry);
        registry.counter("agent.diagnostics.snapshot.refresh.failures");
    }
    private double count(AgentRunStatus status) { var v=snapshot;return v==null?Double.NaN:v.counts().getOrDefault(status.name(),0L); }
    private double unknownAge() { var v=snapshot;return v==null?Double.NaN:v.oldest()==null?0:secondsSince(v.oldest()); }
    private double age() { var v=snapshot;return v==null?Double.NaN:secondsSince(v.refreshed()); }
    private static double secondsSince(Instant since) { return Math.max(0,(Instant.now().toEpochMilli()-since.toEpochMilli())/1000d); }
    @Scheduled(fixedDelayString="${agent.diagnostics.snapshot-delay-ms:30000}")
    public synchronized void refresh() {
        try {
            var next=java.util.Objects.requireNonNull(repository.read()); snapshot=next;
            if(failing) log.info("Agent diagnostics snapshot recovered");
            failing=false;
        } catch(RuntimeException failure) {
            try { registry.counter("agent.diagnostics.snapshot.refresh.failures").increment(); } catch(RuntimeException ignored) { }
            long now=System.nanoTime();
            if(!failing || now-lastWarning>=Duration.ofMinutes(5).toNanos()) {
                log.warn("Agent diagnostics snapshot refresh failed, type={}",failure.getClass().getSimpleName());lastWarning=now;
            }
            failing=true;
        }
    }
}
