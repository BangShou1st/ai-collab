package com.shitulelv.aicollab.agent.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent Worker 执行器并发管理器。
 *
 * <p>支持由系统配置中心在运行期间动态调整 Worker 线程池并发度（1-4），
 * 无须重启服务，且不发生线程泄漏或任务丢失。</p>
 */
@Component
public class AgentWorkerConcurrencyManager {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkerConcurrencyManager.class);
    private final ThreadPoolTaskExecutor executor;

    public AgentWorkerConcurrencyManager(@Qualifier("agentRunTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    public synchronized void setConcurrency(int targetConcurrency) {
        if (targetConcurrency < AgentRunExecutionConfiguration.MIN_WORKER_CONCURRENCY
                || targetConcurrency > AgentRunExecutionConfiguration.MAX_WORKER_CONCURRENCY) {
            log.warn("并发数调整超出合法范围 [{} - {}]: {}",
                    AgentRunExecutionConfiguration.MIN_WORKER_CONCURRENCY,
                    AgentRunExecutionConfiguration.MAX_WORKER_CONCURRENCY,
                    targetConcurrency);
            return;
        }
        ThreadPoolExecutor tpe = executor.getThreadPoolExecutor();
        int currentCore = tpe.getCorePoolSize();
        if (targetConcurrency == currentCore) {
            return;
        }
        log.info("动态调整 Agent Worker 并发数: {} -> {}", currentCore, targetConcurrency);
        if (targetConcurrency > currentCore) {
            executor.setMaxPoolSize(targetConcurrency);
            executor.setCorePoolSize(targetConcurrency);
        } else {
            executor.setCorePoolSize(targetConcurrency);
            executor.setMaxPoolSize(targetConcurrency);
        }
    }

    public int getActiveCount() {
        return executor.getActiveCount();
    }

    public int getPoolSize() {
        return executor.getPoolSize();
    }
}
