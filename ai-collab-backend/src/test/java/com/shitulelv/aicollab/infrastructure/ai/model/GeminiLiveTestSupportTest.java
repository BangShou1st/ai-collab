package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiLiveTestSupportTest {
    @Test
    void anApiKeyAloneDoesNotReadTheCredentialOrInvokeTheProvider() {
        AtomicInteger keyReads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();

        assertThatThrownBy(() -> GeminiLiveTestSupport.withAuthorizedApiKey(
                null,
                "GeminiLiveAcceptanceTest",
                () -> {
                    keyReads.incrementAndGet();
                    return "synthetic-key";
                },
                key -> {
                    providerCalls.incrementAndGet();
                    return key;
                }))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("AI_GEMINI_LIVE_TESTS=true");

        assertThat(keyReads).hasValue(0);
        assertThat(providerCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "True", "1", "yes", " true", "true "})
    void onlyTheExactTrueStringAuthorizesLiveCalls(String optIn) {
        AtomicInteger keyReads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();

        assertThatThrownBy(() -> GeminiLiveTestSupport.withAuthorizedApiKey(
                optIn,
                "GeminiLiveAcceptanceTest",
                () -> {
                    keyReads.incrementAndGet();
                    return "synthetic-key";
                },
                key -> {
                    providerCalls.incrementAndGet();
                    return key;
                }))
                .isInstanceOf(TestAbortedException.class);

        assertThat(keyReads).hasValue(0);
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void explicitAuthorizationWithoutAKeySkipsBeforeTheProviderCall() {
        AtomicInteger providerCalls = new AtomicInteger();

        assertThatThrownBy(() -> GeminiLiveTestSupport.withAuthorizedApiKey(
                "true",
                "GeminiLiveAcceptanceTest",
                () -> "  ",
                key -> {
                    providerCalls.incrementAndGet();
                    return key;
                }))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("GEMINI_API_KEY");

        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void explicitAuthorizationUsesOnlyTheProvidedEnvironmentCredential() {
        AtomicInteger providerCalls = new AtomicInteger();

        String result = GeminiLiveTestSupport.withAuthorizedApiKey(
                "true",
                "GeminiLiveAcceptanceTest",
                () -> " synthetic-key ",
                key -> {
                    providerCalls.incrementAndGet();
                    return key;
                });

        assertThat(result).isEqualTo("synthetic-key");
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void anExplicitFlagDoesNotAuthorizeTheSuiteDuringAnUnfilteredVerify() {
        AtomicInteger keyReads = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();

        assertThatThrownBy(() -> GeminiLiveTestSupport.withAuthorizedApiKey(
                "true",
                "*Test",
                () -> {
                    keyReads.incrementAndGet();
                    return "synthetic-key";
                },
                key -> {
                    providerCalls.incrementAndGet();
                    return key;
                }))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("-Dtest=GeminiLiveAcceptanceTest");

        assertThat(keyReads).hasValue(0);
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void theSurefireSelectorMustNameTheLiveClassRatherThanAWildcard() {
        assertThat(GeminiLiveTestSupport.selectsLiveTestTarget("GeminiLiveAcceptanceTest")).isTrue();
        assertThat(GeminiLiveTestSupport.selectsLiveTestTarget(
                "com.shitulelv.aicollab.infrastructure.ai.model.GeminiLiveAcceptanceTest#phase1_minimalConnection_gemini31FlashLite"))
                .isTrue();
        assertThat(GeminiLiveTestSupport.selectsLiveTestTarget("*Test")).isFalse();
        assertThat(GeminiLiveTestSupport.selectsLiveTestTarget(null)).isFalse();
    }

    @Test
    void repeated503IsInconclusiveAfterTheBoundedAttemptCount() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> GeminiLiveTestSupport.retry503(
                "synthetic Gemini request",
                () -> {
                    attempts.incrementAndGet();
                    throw providerError(503);
                },
                ignored -> { }))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("INCONCLUSIVE")
                .hasMessageContaining("3 attempts");

        assertThat(attempts).hasValue(GeminiLiveTestSupport.MAX_503_ATTEMPTS);
    }

    @Test
    void aSuccessfulRetryCompletesTheLiveOperation() {
        AtomicInteger attempts = new AtomicInteger();

        String result = GeminiLiveTestSupport.retry503(
                "synthetic Gemini request",
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw providerError(503);
                    }
                    return "completed";
                },
                ignored -> { });

        assertThat(result).isEqualTo("completed");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void timeoutIsInconclusiveWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> GeminiLiveTestSupport.retry503(
                "synthetic Gemini request",
                () -> {
                    attempts.incrementAndGet();
                    throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                },
                ignored -> { }))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("INCONCLUSIVE")
                .hasMessageContaining("timed out");

        assertThat(attempts).hasValue(1);
    }

    @Test
    void non503ProviderFailureFailsWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> GeminiLiveTestSupport.retry503(
                "synthetic Gemini request",
                () -> {
                    attempts.incrementAndGet();
                    throw providerError(502);
                },
                ignored -> { }))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getMessage()).contains("HTTP 502"));

        assertThat(attempts).hasValue(1);
    }

    @Test
    void loadingTheLiveTestClassDoesNotChangeProxyPropertiesOrRunLifecycleHooks() throws Exception {
        Map<String, String> proxyProperties = new LinkedHashMap<>();
        for (String name : new String[]{
                "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "java.net.useSystemProxies"}) {
            proxyProperties.put(name, System.getProperty(name));
        }

        Class<?> liveTestClass = Class.forName(
                GeminiLiveAcceptanceTest.class.getName(),
                true,
                Thread.currentThread().getContextClassLoader());

        assertThat(liveTestClass.getDeclaredMethods())
                .noneMatch(method -> isLifecycleHook(method));
        for (Map.Entry<String, String> entry : proxyProperties.entrySet()) {
            assertThat(System.getProperty(entry.getKey())).isEqualTo(entry.getValue());
        }
    }

    private static boolean isLifecycleHook(Method method) {
        return method.isAnnotationPresent(org.junit.jupiter.api.BeforeAll.class)
                || method.isAnnotationPresent(org.junit.jupiter.api.AfterAll.class)
                || method.isAnnotationPresent(org.junit.jupiter.api.BeforeEach.class)
                || method.isAnnotationPresent(org.junit.jupiter.api.AfterEach.class);
    }

    private static BusinessException providerError(int status) {
        return new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "模型服务返回 HTTP " + status);
    }
}
