package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** Reproducible synthetic I/O comparison; never an application latency SLO. */
@EnabledIfSystemProperty(named="p6.benchmark", matches="true")
class AgentToolSchedulerBenchmarkTest {
    @Test void compare() throws Exception {
        List<String> rows = new ArrayList<>(List.of("version,scenario,workers,repeat,submitted,completed,rejected,p50_ms,p95_ms,throughput_s,max_actual_active,max_pending,semaphore_waiting_workers,process_cpu_ms"));
        List<String> samples = new ArrayList<>(List.of("version,scenario,workers,repeat,sample,latency_ms"));
        for(int repeat=1;repeat<=3;repeat++) for(String scenario:List.of("REALISTIC_SAME_USER","REALISTIC_MULTI_USER","STRESS"))
            for(int workers:scenario.equals("STRESS")?List.of(0):List.of(2,4))
                for(boolean modern:List.of(false,true)) trial(modern,scenario,workers,repeat,rows,samples);
        Path out=Path.of("target/p6-benchmark"); Files.createDirectories(out);
        Files.write(out.resolve("summary.csv"),rows); Files.write(out.resolve("latency-samples.csv"),samples);
        Files.writeString(out.resolve("environment.txt"),"java="+System.getProperty("java.runtime.version")+"\nos="+System.getProperty("os.name")+"\nprocessors="+Runtime.getRuntime().availableProcessors()+"\nio_ms=4\nrounds_per_worker=10\nbatch=4\nrepeats=3\nseed=UUID literals / deterministic sequence\n");
        rows.forEach(System.out::println);
    }
    private void trial(boolean modern,String scenario,int workers,int repeat,List<String> rows,List<String> samples) throws Exception {
        var old=modern?null:new LegacyToolScheduler(); var fresh=modern?new AgentToolScheduler():null;
        List<Double> latency=Collections.synchronizedList(new ArrayList<>());
        AtomicInteger actual=new AtomicInteger(), max=new AtomicInteger(), rejected=new AtomicInteger(), pending=new AtomicInteger();
        CountDownLatch gate=new CountDownLatch(scenario.equals("STRESS")?1:0);
        var cpu=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        long cpuStart=cpu.getProcessCpuTime(), begin=System.nanoTime();
        AtomicInteger submitted=new AtomicInteger(); int waiting=0;
        try {
            if(scenario.equals("STRESS")) {
                List<Future<?>> tasks=new ArrayList<>();
                for(int i=0;i<112;i++) {
                    // First 16 hit one run: old workers become permit waiters, new workers remain available.
                    var ctx=AgentToolSchedulerTest.ctx(new UUID(0,i<16?100:100+i),new UUID(0,i<16?200:200+i));
                    tasks.add(submit(modern,old,fresh,ctx,gate,latency,actual,max,rejected,submitted));
                    pending.accumulateAndGet(modern?fresh.snapshot().pending():((ThreadPoolExecutor)old.executor()).getQueue().size(),Math::max);
                }
                waiting=(int)Thread.getAllStackTraces().entrySet().stream().filter(e->e.getKey().getName().equals("agent-tool"))
                        .filter(e->Arrays.stream(e.getValue()).anyMatch(s->s.getClassName().equals(Semaphore.class.getName()))).count();
                gate.countDown(); for(var t:tasks) if(t!=null)t.get(10,TimeUnit.SECONDS);
            } else {
                try(var producers=Executors.newFixedThreadPool(workers)) {
                    List<Future<?>> producersDone=new ArrayList<>();
                    for(int w=0;w<workers;w++) {
                        int worker=w;
                        producersDone.add(producers.submit(()->{
                            for(int round=0;round<10;round++) {
                                List<Future<?>> batch=new ArrayList<>();
                                UUID user=new UUID(0,scenario.equals("REALISTIC_SAME_USER")?100:100+worker);
                                var ctx=AgentToolSchedulerTest.ctx(user,new UUID(0,200+worker));
                                for(int n=0;n<4;n++) {
                                    batch.add(submit(modern,old,fresh,ctx,gate,latency,actual,max,rejected,submitted));
                                    pending.accumulateAndGet(modern?fresh.snapshot().pending():((ThreadPoolExecutor)old.executor()).getQueue().size(),Math::max);
                                }
                                for(var t:batch) if(t!=null) try {t.get(10,TimeUnit.SECONDS);}catch(Exception ex){throw new RuntimeException(ex);}
                            }
                        }));
                    }
                    for(var t:producersDone)t.get(20,TimeUnit.SECONDS);
                }
            }
            double seconds=(System.nanoTime()-begin)/1e9; List<Double> sorted=new ArrayList<>(latency); Collections.sort(sorted);
            String version=modern?"new":"old";
            for(int i=0;i<latency.size();i++) samples.add(String.format(Locale.ROOT,"%s,%s,%d,%d,%d,%.3f",version,scenario,workers,repeat,i,latency.get(i)));
            rows.add(String.format(Locale.ROOT,"%s,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%.2f,%d,%d,%d,%.3f",version,scenario,workers,repeat,submitted.get(),latency.size(),rejected.get(),sorted.get((sorted.size()-1)/2),sorted.get((int)Math.ceil(sorted.size()*.95)-1),latency.size()/seconds,max.get(),pending.get(),waiting,(cpu.getProcessCpuTime()-cpuStart)/1e6));
            if(modern) {assertThat(max.get()).isLessThanOrEqualTo(16); assertThat(pending.get()).isLessThanOrEqualTo(64); AgentToolSchedulerTest.empty(fresh);}
        } finally { gate.countDown(); if(modern)fresh.close();else old.close(); }
    }
    private Future<?> submit(boolean modern,LegacyToolScheduler old,AgentToolScheduler fresh,AgentToolContext ctx,
                             CountDownLatch gate,List<Double> latency,AtomicInteger actual,AtomicInteger max,
                             AtomicInteger rejected,AtomicInteger submitted) {
        long begin=System.nanoTime(); submitted.incrementAndGet();
        Callable<Integer> work=()->{
            int running=actual.incrementAndGet();max.accumulateAndGet(running,Math::max);
            try {gate.await();Thread.sleep(4);return 1;}
            finally {actual.decrementAndGet();latency.add((System.nanoTime()-begin)/1e6);}
        };
        try {
            return modern?fresh.submit(ctx,AgentToolSchedulerTest.deadline(),work):old.executor().submit(()->{try(var ignored=old.acquire(ctx)){return work.call();}});
        }catch(RejectedExecutionException busy){rejected.incrementAndGet();return null;}
    }
}
