package com.shitulelv.aicollab.common.observability;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可观测性 O1/O2 配置契约（docs/design/observability-and-mq-migration.md §2）：
 * 锁定 application.yml 与 pom.xml 中的管理端口隔离、暴露白名单默认值、trace 采样
 * 与 OTLP 导出配置。compose 三层的对应契约由 ai-collab-deploy/scripts 下的
 * prod-compose-contract.mjs / local-compose-contract.mjs 保障，本测试负责应用侧基线。
 */
class ObservabilityContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationYaml() {
        try {
            return new Yaml(new LoaderOptions()).load(
                    Files.newInputStream(Path.of("src/main/resources/application.yml")));
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 application.yml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T property(Map<String, Object> yaml, String dottedKey) {
        Object current = yaml;
        for (String segment : dottedKey.split("\\.")) {
            current = current instanceof Map<?, ?> map ? map.get(segment) : null;
        }
        return (T) current;
    }

    @Test
    void managementPortIsIsolatedBehindEnvVarWithDefault8081() {
        String port = property(applicationYaml(), "management.server.port");
        assertThat(port).isEqualTo("${MANAGEMENT_SERVER_PORT:8081}");
    }

    @Test
    void defaultExposureRemainsHealthAndInfo() {
        String exposure = property(applicationYaml(), "management.endpoints.web.exposure.include");
        // prometheus 由 compose 层 env-var 打开；应用默认值保持既有安全基线不变
        assertThat(exposure).isEqualTo("${MANAGEMENT_ENDPOINTS_EXPOSURE:health,info}");
    }

    @Test
    void consolePatternCarriesTraceAndSpanIdsAlongsideBusinessCorrelationIds() {
        String pattern = property(applicationYaml(), "logging.pattern.console");
        assertThat(pattern).contains("traceId=%X{traceId:-none}");
        assertThat(pattern).contains("spanId=%X{spanId:-none}");
        assertThat(pattern).contains("requestId=%X{requestId:-none}");
        assertThat(pattern).contains("agentRunId=%X{agentRunId:-none}");
    }

    @Test
    void tracingIsFullySampledByDefaultAndOverridable() {
        String probability = property(applicationYaml(), "management.tracing.sampling.probability");
        assertThat(probability).isEqualTo("${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:1.0}");
    }

    @Test
    void otlpExportDefaultsToComposeInternalTempoOverGrpc() {
        String endpoint = property(applicationYaml(), "management.otlp.tracing.endpoint");
        assertThat(endpoint).isEqualTo("${OTEL_EXPORTER_OTLP_ENDPOINT:http://tempo:4317}");
        assertThat((String) property(applicationYaml(), "management.otlp.tracing.transport")).isEqualTo("grpc");
    }

    @Test
    void pomDeclaresPrometheusRegistryAndTracingBridge() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("micrometer-registry-prometheus");
        assertThat(pom).contains("micrometer-tracing-bridge-otel");
        assertThat(pom).contains("opentelemetry-exporter-otlp");
    }
}
