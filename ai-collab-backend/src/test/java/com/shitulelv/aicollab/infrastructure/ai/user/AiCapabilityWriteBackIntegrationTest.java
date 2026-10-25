package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探针写回的 CAS 语义与探针版本锚点迁移（真实 PostgreSQL）。
 * 对应 P7-PROVIDER-ONBOARDING-01 验收场景 T05/T06（数据库语义）。
 */
@Testcontainers(disabledWithoutDocker = true)
class AiCapabilityWriteBackIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static UserAiProviderRepository repository;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new UserAiProviderRepository(jdbc);
    }

    private record Fixture(UUID userId, UUID providerId, OffsetDateTime version) {}

    private Fixture provider(String capabilities) {
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                userId, "probe-" + userId.toString().substring(0, 8), "test-only-hash", "Probe");
        jdbc.update("""
                INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,api_path,model_name,
                    enabled,temperature,max_output_tokens,capabilities,is_default,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,TRUE,0.2,1200,?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, providerId, userId, "c", "OPENAI_COMPATIBLE",
                "https://api.example.com", "/v1/chat/completions", "m", capabilities);
        OffsetDateTime version = jdbc.queryForObject(
                "SELECT updated_at FROM user_ai_provider WHERE id = ?", OffsetDateTime.class, providerId);
        return new Fixture(userId, providerId, version);
    }

    private void probeResult(UUID providerId, String mode, String status, OffsetDateTime testedVersion) {
        jdbc.update("""
                INSERT INTO user_ai_provider_probe_result(provider_id,probe_mode,status,model_name,
                    tested_config_updated_at,tested_at)
                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
                """, providerId, mode, status, "m", testedVersion);
    }

    private String capabilitiesOf(UUID providerId) {
        return jdbc.queryForObject(
                "SELECT capabilities FROM user_ai_provider WHERE id = ?", String.class, providerId);
    }

    @Test void casWriteBackAppendsCapabilitiesAndRebasesProbeEvidence() {
        Fixture fixture = provider("CHAT");
        probeResult(fixture.providerId(), "STREAMING", "PASS", fixture.version());

        Optional<OffsetDateTime> newVersion = repository.addCapabilitiesIfVersionMatches(
                fixture.providerId(), fixture.userId(),
                Set.of(ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS), fixture.version());

        assertThat(newVersion).isPresent();
        assertThat(newVersion.get()).isAfter(fixture.version());
        String capabilities = capabilitiesOf(fixture.providerId());
        assertThat(capabilities).contains("CHAT").contains("STREAMING").contains("NATIVE_TOOLS");
        // 用户原有声明保留（只增不减），无重复项
        assertThat(capabilities.split(",")).doesNotHaveDuplicates();

        // 写回成功后迁移本次运行的模式锚点：刚通过的探针不再被判 STALE
        repository.rebaseProbeTestedVersion(fixture.providerId(), fixture.version(), newVersion.get(),
                List.of("CONNECTIVITY", "STREAMING"));
        OffsetDateTime anchored = jdbc.queryForObject(
                "SELECT tested_config_updated_at FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'STREAMING'",
                OffsetDateTime.class, fixture.providerId());
        assertThat(anchored.toInstant()).isEqualTo(newVersion.get().toInstant());
    }

    @Test void casRejectsWhenConfigVersionMovedOn() {
        // T05：检测期间配置变更（版本已推进）→ 拒绝写回，不改能力
        Fixture fixture = provider("CHAT");
        jdbc.update("UPDATE user_ai_provider SET model_name = 'other-model', updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ?", fixture.providerId());
        OffsetDateTime movedVersion = jdbc.queryForObject(
                "SELECT updated_at FROM user_ai_provider WHERE id = ?", OffsetDateTime.class, fixture.providerId());
        assertThat(movedVersion).isAfter(fixture.version());

        Optional<OffsetDateTime> rejected = repository.addCapabilitiesIfVersionMatches(
                fixture.providerId(), fixture.userId(),
                Set.of(ModelCapability.NATIVE_TOOLS), fixture.version());

        assertThat(rejected).isEmpty();
        assertThat(capabilitiesOf(fixture.providerId())).isEqualTo("CHAT");
    }

    @Test void rebaseOnlyTouchesMatchingVersionAndRequestedModes() {
        Fixture fixture = provider("CHAT");
        probeResult(fixture.providerId(), "STREAMING", "PASS", fixture.version());
        probeResult(fixture.providerId(), "NATIVE_TOOLS", "FAIL", fixture.version());
        OffsetDateTime otherVersion = fixture.version().plusSeconds(30);
        probeResult(fixture.providerId(), "CONNECTIVITY", "PASS", otherVersion);

        Optional<OffsetDateTime> newVersion = repository.addCapabilitiesIfVersionMatches(
                fixture.providerId(), fixture.userId(), Set.of(ModelCapability.STREAMING), fixture.version());
        assertThat(newVersion).isPresent();
        repository.rebaseProbeTestedVersion(fixture.providerId(), fixture.version(), newVersion.get(),
                List.of("STREAMING"));

        // 旧版本 + 本次运行的模式 → 迁移
        assertThat(jdbc.queryForObject(
                "SELECT tested_config_updated_at FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'STREAMING'",
                OffsetDateTime.class, fixture.providerId()).toInstant())
                .isEqualTo(newVersion.get().toInstant());
        // 旧版本但不在本次运行模式列表 → 保持旧锚点（判 STALE，符合“配置已变更”语义）
        assertThat(jdbc.queryForObject(
                "SELECT tested_config_updated_at FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'NATIVE_TOOLS'",
                OffsetDateTime.class, fixture.providerId()).toInstant())
                .isEqualTo(fixture.version().toInstant());
        // 新版本锚点不受影响
        assertThat(jdbc.queryForObject(
                "SELECT tested_config_updated_at FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'CONNECTIVITY'",
                OffsetDateTime.class, fixture.providerId()).toInstant())
                .isEqualTo(otherVersion.toInstant());
    }
}
