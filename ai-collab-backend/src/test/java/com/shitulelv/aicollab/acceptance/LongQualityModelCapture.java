package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.LinkedHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Opt-in test host observation only. Production models and transports execute unchanged. */
@TestConfiguration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "AI_LONG_QUALITY_CAPTURE", havingValue = "true")
public class LongQualityModelCapture {
    @Bean static BeanPostProcessor captureQualityModelRequests() {
        return new BeanPostProcessor() {
            final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof com.shitulelv.aicollab.agent.application.runtime.RoutingAgentModelExecutor)
                        && !(bean instanceof com.shitulelv.aicollab.planning.application.TaskPlanModelClient)) return bean;
                var proxy = new ProxyFactory(bean);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    String method = invocation.getMethod().getName();
                    if (!java.util.List.of("callModel", "callModelWithoutTools", "generate").contains(method)) return invocation.proceed();
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("at", java.time.OffsetDateTime.now().toString());
                    entry.put("method", method);
                    entry.put("input", invocation.getArguments());
                    try {
                        Object result = invocation.proceed();
                        entry.put("result", result);
                        return result;
                    } catch (Throwable failure) {
                        entry.put("error", failure.getClass().getSimpleName());
                        if (failure instanceof com.shitulelv.aicollab.common.exception.BusinessException business)
                            entry.put("errorCode", business.getErrorCode().name());
                        throw failure;
                    } finally {
                        synchronized (json) {
                            Files.writeString(Path.of("target/long-quality-model-calls-private.jsonl"), json.writeValueAsString(entry) + "\n",
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        }
                    }
                });
                return proxy.getProxy();
            }
        };
    }
}
