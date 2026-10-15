package com.shitulelv.aicollab.agent.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent Worker 执行隔离配置。
 *
 * <p>调度线程（{@link AgentRuntimeJob}）只负责短事务：过期审批收口与任务派发；
 * 耗时的模型轮次、工具执行统一在 {@code agentRunTaskExecutor} 中执行，
 * 避免阻塞 Spring 定时线程。</p>
 *
 * <p>核心与最大线程数相等且队列容量为 0，拒绝策略为 {@link ThreadPoolExecutor.AbortPolicy}：
 * 满载时直接拒绝本次派发，调度线程随即结束本轮 tick，不排队、不回退到调用方线程执行。
 * 等待中的 Run 仍保持 QUEUED，由后续 tick 或数据库租约恢复机制接管。</p>
 *
 * <p>线程池由 Spring 管理生命周期：容器初始化时启动、容器关闭时销毁。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "agent", name = "enabled", havingValue = "true")
public class AgentRunExecutionConfiguration {
    static final int DEFAULT_WORKER_CONCURRENCY = 2;
    static final int MIN_WORKER_CONCURRENCY = 1;
    static final int MAX_WORKER_CONCURRENCY = 4;

    @Bean("agentRunTaskExecutor")
    ThreadPoolTaskExecutor agentRunTaskExecutor(
            @Value("${agent.worker-concurrency:2}") int workerConcurrency) {
        if (workerConcurrency < MIN_WORKER_CONCURRENCY || workerConcurrency > MAX_WORKER_CONCURRENCY) {
            throw new IllegalStateException(
                    "agent.worker-concurrency 必须在 " + MIN_WORKER_CONCURRENCY + "-"
                            + MAX_WORKER_CONCURRENCY + " 之间，当前值为 " + workerConcurrency);
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workerConcurrency);
        executor.setMaxPoolSize(workerConcurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("agent-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关闭时不再接收新派发，但不等待正在执行的 Run 结束：
        // 未结算的 Run 由既有数据库租约过期与重启接管机制收口，避免拖住容器关闭流程。
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
