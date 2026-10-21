package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.opentest4j.TestAbortedException;

import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

final class GeminiLiveTestSupport {
    static final String LIVE_TESTS_ENV = "AI_GEMINI_LIVE_TESTS";
    static final String API_KEY_ENV = "GEMINI_API_KEY";
    static final String SUREFIRE_TEST_SELECTOR_PROPERTY = "test";
    static final String LIVE_TEST_CLASS = "com.shitulelv.aicollab.infrastructure.ai.model.GeminiLiveAcceptanceTest";
    static final int MAX_503_ATTEMPTS = 3;

    private GeminiLiveTestSupport() {
    }

    static <T> T withAuthorizedApiKey(Function<String, T> liveCall) {
        return withAuthorizedApiKey(
                System.getenv(LIVE_TESTS_ENV),
                System.getProperty(SUREFIRE_TEST_SELECTOR_PROPERTY),
                () -> System.getenv(API_KEY_ENV),
                liveCall);
    }

    static String requireApiKey() {
        return requireApiKey(
                System.getenv(LIVE_TESTS_ENV),
                System.getProperty(SUREFIRE_TEST_SELECTOR_PROPERTY),
                () -> System.getenv(API_KEY_ENV));
    }

    static String requireApiKey(String optIn, String testSelector, Supplier<String> apiKeySource) {
        Assumptions.assumeTrue(
                "true".equals(optIn),
                "SKIPPED: set AI_GEMINI_LIVE_TESTS=true to authorize real Gemini acceptance calls");
        Assumptions.assumeTrue(
                selectsLiveTestTarget(testSelector),
                "SKIPPED: select GeminiLiveAcceptanceTest explicitly with -Dtest=GeminiLiveAcceptanceTest");

        String apiKey = apiKeySource.get();
        Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "SKIPPED: GEMINI_API_KEY is required after explicit Gemini live authorization");

        return apiKey.strip();
    }

    static <T> T withAuthorizedApiKey(
            String optIn,
            String testSelector,
            Supplier<String> apiKeySource,
            Function<String, T> liveCall) {
        return liveCall.apply(requireApiKey(optIn, testSelector, apiKeySource));
    }

    static boolean selectsLiveTestTarget(String testSelector) {
        if (testSelector == null || testSelector.isBlank()) {
            return false;
        }
        for (String selection : testSelector.split(",")) {
            String selectedClass = selection.strip();
            int methodSeparator = selectedClass.indexOf('#');
            if (methodSeparator >= 0) {
                selectedClass = selectedClass.substring(0, methodSeparator);
            }
            if (selectedClass.equals("GeminiLiveAcceptanceTest") || selectedClass.equals(LIVE_TEST_CLASS)) {
                return true;
            }
        }
        return false;
    }

    static <T> T retry503(String operation, Supplier<T> request) {
        return retry503(operation, request, GeminiLiveTestSupport::sleepBeforeRetry);
    }

    static <T> T retry503(String operation, Supplier<T> request, LongConsumer waitBeforeRetry) {
        for (int attempt = 1; attempt <= MAX_503_ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT) {
                    throw inconclusiveTimeout(operation, exception);
                }
                if (!isHttp503(exception)) {
                    throw exception;
                }
                if (attempt == MAX_503_ATTEMPTS) {
                    throw inconclusive503(operation, attempt);
                }
                waitBeforeRetry.accept(3_000L * attempt);
            }
        }
        throw new AssertionError("503 retry loop exited without a result");
    }

    static boolean isHttp503(Throwable failure) {
        return failure instanceof BusinessException exception
                && exception.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR
                && exception.getMessage() != null
                && exception.getMessage().contains("HTTP 503");
    }

    static TestAbortedException inconclusive503(String operation, int attempts) {
        return new TestAbortedException(
                "INCONCLUSIVE: " + operation + " returned HTTP 503 after " + attempts + " attempts");
    }

    static TestAbortedException inconclusiveTimeout(String operation, Throwable cause) {
        return new TestAbortedException(
                "INCONCLUSIVE: " + operation + " timed out before its acceptance assertions completed",
                cause);
    }

    private static void sleepBeforeRetry(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TestAbortedException("INCONCLUSIVE: retry wait was interrupted", exception);
        }
    }
}
