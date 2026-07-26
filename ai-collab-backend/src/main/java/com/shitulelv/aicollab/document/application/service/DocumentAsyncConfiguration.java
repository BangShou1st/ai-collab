package com.shitulelv.aicollab.document.application.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class DocumentAsyncConfiguration {
    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 100;
    private static final int AWAIT_TERMINATION_SECONDS = 30;

    @Bean("documentTaskExecutor")
    ThreadPoolTaskExecutor documentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("document-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }
}
