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
 * 对应 P7-PROVIDER-ONBOARDING-01 验收场景 T05/T06（数据库语义），
 * 并覆盖 V74 引入的独立能力配置版本语义（设默认/相同配置保存不推进版本）。
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

    private record Fixture(UUID userId, UUID providerId, long version) {}

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
        long version = jdbc.queryForObject(
                "SELECT capability_config_version FROM user_ai_provider WHERE id = ?", Long.class, providerId);
        return new Fixture(userId, providerId, version);
    }

    private void probeResult(UUID providerId, String mode, String status, Long testedVersion) {
        jdbc.update("""
                INSERT INTO user_ai_provider_probe_result(provider_id,probe_mode,status,model_name,
                    tested_config_updated_at,tested_config_version,tested_at)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP)
                """, providerId, mode, status, "m", testedVersion);
    }

    private String capabilitiesOf(UUID providerId) {
        return jdbc.queryForObject(
                "SELECT capabilities FROM user_ai_provider WHERE id = ?", String.class, providerId);
    }

    @Test void casWriteBackAppendsCapabilitiesAndRebasesProbeEvidence() {
        Fixture fixture = provider("CHAT");
        probeResult(fixture.providerId(), "STREAMING", "PASS", fixture.version());

        Optional<Long> newVersion = repository.addCapabilitiesIfVersionMatches(
                fixture.providerId(), fixture.userId(),
                Set.of(ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS), fixture.version());

        assertThat(newVersion).isPresent();
        assertThat(newVersion.get()).isEqualTo(fixture.version() + 1);
        String capabilities = capabilitiesOf(fixture.providerId());
        assertThat(capabilities).contains("CHAT").contains("STREAMING").contains("NATIVE_TOOLS");
        // 用户原有声明保留（只增不减），无重复项
        assertThat(capabilities.split(",")).doesNotHaveDuplicates();

        // 写回成功后迁移本次运行的模式锚点：刚通过的探针不再被判 STALE
        repository.rebaseProbeTestedVersion(fixture.providerId(), fixture.version(), newVersion.get(),
                List.of("CONNECTIVITY", "STREAMING"));
        Long anchored = jdbc.queryForObject(
                "SELECT tested_config_version FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'STREAMING'",
                Long.class, fixture.providerId());
        assertThat(anchored).isEqualTo(newVersion.get());
    }

    @Test void casRejectsWhenConfigVersionMovedOn() {
        // T05：检测期间配置变更（版本已推进）→ 拒绝写回，不改能力
        Fixture fixture = provider("CHAT");
        jdbc.update("UPDATE user_ai_provider SET model_name = 'other-model', "
                + "capability_config_version = capability_config_version + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ?", fixture.providerId());
        long movedVersion = jdbc.queryForObject(
                "SELECT capability_config_version FROM user_ai_provider WHERE id = ?",
                Long.class, fixture.providerId());
        assertThat(movedVersion).isEqualTo(fixture.version() + 1);

        Optional<Long> rejected = repository.addCapabilitiesIfVersionMatches(
                fixture.providerId(), fixture.userId(),
                Set.of(ModelCapability.NATIVE_TOOLS), fixture.version());

        assertThat(rejected).isEmpty();
        assertThat(capabilitiesOf(fixture.providerId())).isEqualTo("CHAT");
    }

    @Test void rebaseOnlyTouchesMatchingVersionAndRequestedModes() {
        Fixture fixture = provider("CHAT");
        probeResult(fixture.providerId(), "STREAMING", "PASS", fixture.version());
        probeResult(fixture.providerId(), "NATIVE_TOOLS", "FAIL", fixture.version());
        Long otherVersion = fixture.version() + 30;
        probeResult(fixture.providerId(), "CONNECTIVITY", "PASS", otherVersion);

        Optional<Long> newVersion = repository.addCapabilitiesIfVersionMatches(
                fixture.providerId(), fixture.userId(), Set.of(ModelCapability.STREAMING), fixture.version());
        assertThat(newVersion).isPresent();
        repository.rebaseProbeTestedVersion(fixture.providerId(), fixture.version(), newVersion.get(),
                List.of("STREAMING"));

        // 旧版本 + 本次运行的模式 → 迁移
        assertThat(jdbc.queryForObject(
                "SELECT tested_config_version FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'STREAMING'",
                Long.class, fixture.providerId()))
                .isEqualTo(newVersion.get());
        // 旧版本但不在本次运行模式列表 → 保持旧锚点（判 STALE，符合“配置已变更”语义）
        assertThat(jdbc.queryForObject(
                "SELECT tested_config_version FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'NATIVE_TOOLS'",
                Long.class, fixture.providerId()))
                .isEqualTo(fixture.version());
        // 新版本锚点不受影响
        assertThat(jdbc.queryForObject(
                "SELECT tested_config_version FROM user_ai_provider_probe_result "
                        + "WHERE provider_id = ? AND probe_mode = 'CONNECTIVITY'",
                Long.class, fixture.providerId()))
                .isEqualTo(otherVersion);
    }

    @Test void defaultToggleKeepsCapabilityConfigVersion() {
        // 设默认/取消默认只改行数据，不推进能力配置版本：既有探针证据继续有效（A1）
        Fixture fixture = provider("CHAT");
        repository.clearDefault(fixture.userId());
        assertThat(repository.markDefault(fixture.userId(), fixture.providerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT capability_config_version FROM user_ai_provider WHERE id = ?",
                Long.class, fixture.providerId())).isZero();
    }

    @Test void identicalSaveAndCosmeticChangesKeepCapabilityConfigVersion() {
        // 相同配置保存/仅改显示名称：版本与 updated_at 都不推进（A2 + 显示名语义）
        Fixture fixture = provider("CHAT");
        UserAiProvider before = repository.findById(fixture.providerId()).orElseThrow();

        repository.save(copyOf(before, "renamed", before.modelName()));
        UserAiProvider renamed = repository.findById(fixture.providerId()).orElseThrow();
        assertThat(renamed.capabilityConfigVersion()).isZero();
        assertThat(renamed.name()).isEqualTo("renamed");
        assertThat(renamed.updatedAt().toInstant()).isEqualTo(before.updatedAt().toInstant());

        // 改模型 ID（真实请求行为变化）：版本与 updated_at 推进
        repository.save(copyOf(renamed, renamed.name(), "changed-model"));
        UserAiProvider bumped = repository.findById(fixture.providerId()).orElseThrow();
        assertThat(bumped.capabilityConfigVersion()).isEqualTo(fixture.version() + 1);
        assertThat(bumped.updatedAt()).isAfter(before.updatedAt());

        // 版本推进后的纯名称变更：版本停在当前值，updated_at 不再变化
        OffsetDateTime bumpedAt = bumped.updatedAt();
        repository.save(copyOf(bumped, "renamed-again", bumped.modelName()));
        UserAiProvider renamedAgain = repository.findById(fixture.providerId()).orElseThrow();
        assertThat(renamedAgain.capabilityConfigVersion()).isEqualTo(fixture.version() + 1);
        assertThat(renamedAgain.updatedAt().toInstant()).isEqualTo(bumpedAt.toInstant());
    }

    private static UserAiProvider copyOf(UserAiProvider source, String name, String modelName) {
        return new UserAiProvider(source.id(), source.userId(), name, source.providerType(),
                source.baseUrl(), source.apiPath(), source.encryptedApiKey(), modelName,
                source.enabled(), source.temperature(), source.maxOutputTokens(), source.capabilities(),
                source.isDefault(), source.createdAt(), source.updatedAt(), source.presetCode(),
                source.capabilityConfigVersion());
    }
}
