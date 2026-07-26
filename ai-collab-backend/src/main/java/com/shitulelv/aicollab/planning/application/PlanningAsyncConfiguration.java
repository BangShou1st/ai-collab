package com.shitulelv.aicollab.planning.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class PlanningAsyncConfiguration {
    @Bean("planningTaskExecutor")
    Executor planningTaskExecutor(
            @Value("${planning.executor.core-size:2}") int core,
            @Value("${planning.executor.max-size:4}") int max,
            @Value("${planning.executor.queue-capacity:20}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("planning-");
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.initialize();
        return executor;
    }
}
