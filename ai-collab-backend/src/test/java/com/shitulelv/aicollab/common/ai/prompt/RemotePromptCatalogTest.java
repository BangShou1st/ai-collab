package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 远端目录：启动预取、二次 sha256、整批原子替换、失败保留上次成功版本、刷新调度。 */
class RemotePromptCatalogTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofMillis(150);

    private static PromptRegistryProperties properties(String baseUrl, Duration interval) {
        return new PromptRegistryProperties(true, baseUrl, interval, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    private static RestClient restClient() {
        return restClient(READ_TIMEOUT);
    }

    private static RestClient restClient(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Test
    void loadsRealPackOnStartup() throws Exception {
        PromptPackFixtures.Pack real = PromptPackFixtures.realPack();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        try (PromptWorkerStub stub = new PromptWorkerStub(real);
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ZERO), restClient(), metrics)) {
            assertThat(stub.manifestRequests()).isEqualTo(1);
            assertThat(stub.documentRequests()).isEqualTo(14);
            assertThat(catalog.source()).isEqualTo("REMOTE");
            assertThat(catalog.readinessFailure()).isNull();
            assertThat(catalog.versions()).hasSize(14)
                    .containsEntry("knowledge.system", "2026-11-11.2");

            PromptTemplate system = catalog.require("knowledge.system");
            assertThat(system.text()).isEqualTo(real.texts().get("knowledge.system"));
            assertThat(system.sha256()).isEqualTo(real.entries().get("knowledge.system").sha256());
            assertThat(system.chars()).isEqualTo(real.entries().get("knowledge.system").chars());
            assertThat(system.variables()).containsExactly("timeContext");
            assertThat(catalog.manifestFingerprint())
                    .isEqualTo(new BundledPromptCatalog().manifestFingerprint());

            assertThat(metrics.get("prompt.catalog.refresh").tag("result", "success")
                    .counter().count()).isEqualTo(1.0);
            assertThat(metrics.get("prompt.catalog.entries").tag("source", "REMOTE")
                    .gauge().value()).isEqualTo(14.0);
        }
    }

    @Test
    void tamperedDocumentTextKeepsLastSuccessfulSnapshot() throws Exception {
        PromptPackFixtures.Pack first = PromptPackFixtures.syntheticPack("1");
        PromptPackFixtures.Pack second = PromptPackFixtures.syntheticPack("2");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        try (PromptWorkerStub stub = new PromptWorkerStub(first);
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ZERO), restClient(), metrics)) {
            assertThat(catalog.require("knowledge.system").version()).isEqualTo("2026-11-11.1");

            stub.setPack(second);
            stub.tamperText("knowledge.system", "TAMPERED-BUT-NOT-LOGGED");
            assertThatCode(catalog::refreshNow).doesNotThrowAnyException();

            // sha256 不符：整批不生效，缓存仍是上一批
            assertThat(catalog.require("knowledge.system").version()).isEqualTo("2026-11-11.1");
            assertThat(catalog.require("knowledge.system").text())
                    .isEqualTo(first.texts().get("knowledge.system"));
            assertThat(catalog.versions().values()).containsOnly("2026-11-11.1");
            assertThat(catalog.readinessFailure()).contains("sha256 mismatch");
            assertThat(metrics.get("prompt.catalog.refresh").tag("result", "failure")
                    .counter().count()).isEqualTo(1.0);

            // 恢复后下一次刷新整批换新
            stub.clearTamperedText();
            catalog.refreshNow();
            assertThat(catalog.require("knowledge.system").version()).isEqualTo("2026-11-11.2");
            assertThat(catalog.readinessFailure()).isNull();
        }
    }

    @Test
    void documentShaFieldMustMatchTextEvenWhenManifestShaMatches() throws Exception {
        PromptPackFixtures.Pack first = PromptPackFixtures.syntheticPack("1");
        PromptPackFixtures.Pack second = PromptPackFixtures.syntheticPack("2");

        try (PromptWorkerStub stub = new PromptWorkerStub(first);
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ZERO), restClient(),
                     new SimpleMeterRegistry())) {
            stub.setPack(second);
            // 正文与 manifest sha 一致，但文档自报的 sha256 是另一个值 → 必须拒绝
            stub.overrideDocumentSha("knowledge.system", "f".repeat(64));
            catalog.refreshNow();

            assertThat(catalog.require("knowledge.system").version()).isEqualTo("2026-11-11.1");
            assertThat(catalog.readinessFailure()).contains("sha256 mismatch with document");
        }
    }

    @Test
    void http5xxDuringRefreshKeepsLastSuccessfulSnapshot() throws Exception {
        PromptPackFixtures.Pack first = PromptPackFixtures.syntheticPack("1");

        try (PromptWorkerStub stub = new PromptWorkerStub(first);
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ZERO), restClient(),
                     new SimpleMeterRegistry())) {
            stub.manifestStatus(503);
            catalog.refreshNow();

            assertThat(catalog.require("knowledge.system").version()).isEqualTo("2026-11-11.1");
            assertThat(catalog.readinessFailure()).contains("http 503");
        }
    }

    @Test
    void batchIsReplacedAtomically_onlyWhenAllFourteenSucceed() throws Exception {
        PromptPackFixtures.Pack first = PromptPackFixtures.syntheticPack("1");
        PromptPackFixtures.Pack second = PromptPackFixtures.syntheticPack("2");
        String lastKey = PromptKeys.all().get(PromptKeys.all().size() - 1);

        try (PromptWorkerStub stub = new PromptWorkerStub(first);
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ZERO), restClient(),
                     new SimpleMeterRegistry())) {
            stub.setPack(second);
            stub.failDocument(lastKey, 500);
            catalog.refreshNow();

            // 13 条新数据已成功，但第 14 条失败 → 整批不生效，不能出现半套新数据
            for (String key : PromptKeys.all()) {
                assertThat(catalog.require(key).version()).isEqualTo("2026-11-11.1");
                assertThat(catalog.require(key).text()).isEqualTo(first.texts().get(key));
            }
            assertThat(stub.documentRequests()).isEqualTo(14 + 14);

            stub.clearDocumentFailure();
            catalog.refreshNow();
            for (String key : PromptKeys.all()) {
                assertThat(catalog.require(key).version())
                        .withFailMessage("readiness=%s", catalog.readinessFailure())
                        .isEqualTo("2026-11-11.2");
                assertThat(catalog.require(key).text()).isEqualTo(second.texts().get(key));
            }
        }
    }

    @Test
    void startupFailureCategories_failFast() throws Exception {
        PromptPackFixtures.Pack pack = PromptPackFixtures.syntheticPack("1");

        String closedUrl;
        try (PromptWorkerStub stub = new PromptWorkerStub(pack)) {
            closedUrl = stub.baseUrl();
        }
        // 传输层失败的具体分类取决于运行环境的代理/OS 行为（本机可见 502 或连接超时，
        // 而不是连接拒绝），因此这里只断言两件稳定的事：失败发生在 manifest 阶段（即没有任何
        // 半套缓存可用），并且 require 快速失败为 AI_PROMPT_UNAVAILABLE。分类短语由下面的
        // 503/404/非法 JSON/schemaVersion 用例做确定性断言。
        assertStartupUnavailable(properties(closedUrl, Duration.ZERO), "manifest ");

        try (PromptWorkerStub stub = new PromptWorkerStub(pack)) {
            // 读超时必须小于 stub 的延时，否则请求会成功而不是超时
            stub.delayMillis(600);
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO),
                    SHORT_READ_TIMEOUT, "timeout");
        }
        try (PromptWorkerStub stub = new PromptWorkerStub(pack)) {
            stub.manifestStatus(503);
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "http 503");
        }
        try (PromptWorkerStub stub = new PromptWorkerStub(pack)) {
            stub.manifestStatus(404);
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "http 404");
        }
        try (PromptWorkerStub stub = new PromptWorkerStub(pack)) {
            stub.manifestBody("{ not json");
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "response invalid");
        }
        try (PromptWorkerStub stub = new PromptWorkerStub(pack)) {
            stub.manifestBody("{\"schemaVersion\":2,\"prompts\":[]}");
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "schemaVersion");
        }
        try (PromptWorkerStub stub = new PromptWorkerStub(
                withoutKey(pack, "knowledge.user"))) {
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "missing from manifest");
        }
    }

    @Test
    void contentValidationRejectsCharsAndVariablesMismatch() throws Exception {
        PromptPackFixtures.Pack pack = PromptPackFixtures.syntheticPack("1");

        PromptPackFixtures.Pack badChars = PromptPackFixtures.withChars(pack,
                "knowledge.system", pack.entries().get("knowledge.system").chars() + 1);
        try (PromptWorkerStub stub = new PromptWorkerStub(badChars)) {
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "chars mismatch");
        }

        PromptPackFixtures.Pack withVariable = PromptPackFixtures.withText(pack,
                "knowledge.system", "变量 = {{ locale }}", List.of("locale"));
        try (PromptWorkerStub stub = new PromptWorkerStub(withVariable)) {
            stub.overrideDocumentVariables("knowledge.system", List.of());
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "variables mismatch");
        }
    }

    @Test
    void documentHttpErrorAtStartupFailsFast() throws Exception {
        PromptPackFixtures.Pack pack = PromptPackFixtures.syntheticPack("1");
        String lastKey = PromptKeys.all().get(PromptKeys.all().size() - 1);

        try (PromptWorkerStub stub = new PromptWorkerStub(pack)) {
            stub.failDocument(lastKey, 503);
            assertStartupUnavailable(properties(stub.baseUrl(), Duration.ZERO), "document http 503");
        }
    }

    @Test
    void missingKeyAfterSuccessCountsUnavailableMetric() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (PromptWorkerStub stub = new PromptWorkerStub(PromptPackFixtures.syntheticPack("1"));
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ZERO), restClient(), metrics)) {
            assertThatThrownBy(() -> catalog.require("knowledge.unknown"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
            assertThat(metrics.get("prompt.catalog.unavailable")
                    .tag("key", "knowledge.unknown").counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    void zeroOrNegativeIntervalDisablesPeriodicRefresh() throws Exception {
        try (PromptWorkerStub stub = new PromptWorkerStub(PromptPackFixtures.syntheticPack("1"));
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ZERO), restClient(),
                     new SimpleMeterRegistry())) {
            assertThat(stub.manifestRequests()).isEqualTo(1);
            Thread.sleep(250);
            assertThat(stub.manifestRequests()).isEqualTo(1);
        }
        try (PromptWorkerStub stub = new PromptWorkerStub(PromptPackFixtures.syntheticPack("1"));
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ofMillis(-1)), restClient(),
                     new SimpleMeterRegistry())) {
            assertThat(stub.manifestRequests()).isEqualTo(1);
            Thread.sleep(250);
            assertThat(stub.manifestRequests()).isEqualTo(1);
        }
    }

    @Test
    void positiveIntervalRefreshesPeriodically() throws Exception {
        try (PromptWorkerStub stub = new PromptWorkerStub(PromptPackFixtures.syntheticPack("1"));
             RemotePromptCatalog catalog = new RemotePromptCatalog(
                     properties(stub.baseUrl(), Duration.ofMillis(100)), restClient(),
                     new SimpleMeterRegistry())) {
            await(() -> stub.manifestRequests() >= 3, Duration.ofSeconds(5));
            assertThat(stub.manifestRequests()).isGreaterThanOrEqualTo(3);
        }
    }

    private static void assertStartupUnavailable(PromptRegistryProperties properties,
                                                 String expectedReason) {
        assertStartupUnavailable(properties, READ_TIMEOUT, expectedReason);
    }

    private static void assertStartupUnavailable(PromptRegistryProperties properties,
                                                 Duration readTimeout,
                                                 String expectedReason) {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (RemotePromptCatalog catalog = new RemotePromptCatalog(
                properties, restClient(readTimeout), metrics)) {
            assertThat(catalog.versions()).isEmpty();
            assertThat(catalog.readinessFailure()).contains(expectedReason);
            assertThatThrownBy(() -> catalog.require("knowledge.system"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode())
                                    .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
            assertThat(metrics.get("prompt.catalog.refresh").tag("result", "failure")
                    .counter().count()).isEqualTo(1.0);
            assertThat(metrics.get("prompt.catalog.entries").tag("source", "REMOTE")
                    .gauge().value()).isZero();
        }
    }

    private static PromptPackFixtures.Pack withoutKey(PromptPackFixtures.Pack pack,
                                                      String missingKey) {
        Map<String, PromptPackFixtures.Entry> entries = new LinkedHashMap<>(pack.entries());
        Map<String, String> texts = new LinkedHashMap<>(pack.texts());
        entries.remove(missingKey);
        texts.remove(missingKey);
        return new PromptPackFixtures.Pack(Map.copyOf(entries), Map.copyOf(texts));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待条件超时: " + timeout);
    }
}
