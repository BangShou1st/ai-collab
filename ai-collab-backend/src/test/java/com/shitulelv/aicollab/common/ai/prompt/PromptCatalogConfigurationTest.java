package com.shitulelv.aicollab.common.ai.prompt;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 装配语义：默认零外呼 bundled；显式 enabled 才走 remote；远端启动失败不阻断上下文。 */
class PromptCatalogConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PromptCatalogConfiguration.class);

    @Test
    void defaultsToBundledAndMakesNoOutboundRequest() throws Exception {
        try (PromptWorkerStub stub = new PromptWorkerStub(PromptPackFixtures.realPack())) {
            runner.withPropertyValues(
                            "prompt.registry.base-url=" + stub.baseUrl(),
                            "prompt.registry.refresh-interval=PT0S")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(PromptCatalog.class);
                        PromptCatalog catalog = context.getBean(PromptCatalog.class);
                        assertThat(catalog).isInstanceOf(BundledPromptCatalog.class);
                        assertThat(catalog.source()).isEqualTo("BUNDLED");
                        assertThat(catalog.require("knowledge.system").version())
                                .isEqualTo("2026-11-11.2");
                    });
            assertThat(stub.manifestRequests()).isZero();
            assertThat(stub.documentRequests()).isZero();
        }
    }

    @Test
    void enabledTrueUsesRemoteCatalog() throws Exception {
        try (PromptWorkerStub stub = new PromptWorkerStub(PromptPackFixtures.realPack())) {
            runner.withPropertyValues(
                            "prompt.registry.enabled=true",
                            "prompt.registry.base-url=" + stub.baseUrl(),
                            "prompt.registry.refresh-interval=PT0S",
                            "prompt.registry.connect-timeout=PT1S",
                            "prompt.registry.read-timeout=PT1S")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(PromptCatalog.class);
                        PromptCatalog catalog = context.getBean(PromptCatalog.class);
                        assertThat(catalog).isInstanceOf(RemotePromptCatalog.class);
                        assertThat(catalog.source()).isEqualTo("REMOTE");
                        assertThat(catalog.require("knowledge.system").version())
                                .isEqualTo("2026-11-11.2");
                    });
            assertThat(stub.manifestRequests()).isEqualTo(1);
            assertThat(stub.documentRequests()).isEqualTo(14);
        }
    }

    @Test
    void remoteStartupFailureDoesNotBreakContext_andRequireFailsFast() throws Exception {
        String closedUrl;
        try (PromptWorkerStub stub = new PromptWorkerStub(PromptPackFixtures.realPack())) {
            closedUrl = stub.baseUrl();
        }
        runner.withPropertyValues(
                        "prompt.registry.enabled=true",
                        "prompt.registry.base-url=" + closedUrl,
                        "prompt.registry.refresh-interval=PT0S",
                        "prompt.registry.connect-timeout=300ms",
                        "prompt.registry.read-timeout=300ms")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PromptCatalog.class);
                    PromptCatalog catalog = context.getBean(PromptCatalog.class);
                    assertThat(catalog).isInstanceOf(RemotePromptCatalog.class);
                    assertThat(((RemotePromptCatalog) catalog).readinessFailure())
                            .contains("unreachable");
                    assertThatThrownBy(() -> catalog.require("knowledge.system"))
                            .isInstanceOfSatisfying(BusinessException.class,
                                    failure -> assertThat(failure.getErrorCode())
                                            .isEqualTo(ErrorCode.AI_PROMPT_UNAVAILABLE));
                });
    }

    @Test
    void propertiesBindDocumentedDefaults() {
        runner.run(context -> {
            PromptRegistryProperties properties = context.getBean(PromptRegistryProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.baseUrl()).isEqualTo("http://ai-worker-api:9100");
            assertThat(properties.refreshInterval()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void nonPositiveTimeoutsFallBackToDefaultsInsteadOfBlockingStartup() {
        runner.withPropertyValues(
                        "prompt.registry.connect-timeout=PT0S",
                        "prompt.registry.read-timeout=-PT1S")
                .run(context -> {
                    PromptRegistryProperties properties =
                            context.getBean(PromptRegistryProperties.class);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }
}
