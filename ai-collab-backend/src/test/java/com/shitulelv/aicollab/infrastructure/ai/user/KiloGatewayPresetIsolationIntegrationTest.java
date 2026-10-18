package com.shitulelv.aicollab.infrastructure.ai.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kilo 预设的真实 PostgreSQL 集成验证：
 * 与 Zen 双供应商并存、断开互不影响、V65 约束生效、Flyway 重复启动不重复执行迁移。
 */
@Testcontainers(disabledWithoutDocker = true)
class KiloGatewayPresetIsolationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static UserAiProviderRepository repository;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new UserAiProviderRepository(jdbc);
    }

    private static UUID user(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "kilo-" + tag + "-" + id.toString().substring(0, 8), "test-only-hash", "Kilo " + tag);
        return id;
    }

    private static UUID provider(UUID user, String name, String preset) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,model_name,is_default,preset_code)"
                        + " VALUES (?,?,?,?,?,?,?,?)", id, user, name, "OPENAI_COMPATIBLE",
                "https://ai-gateway.kilo.ai/api/v1", "poolside/laguna-s-2.1:free", false, preset);
        return id;
    }

    private static void assign(UUID user, String purpose, UUID provider) {
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,?,?)",
                user, purpose, provider);
    }

    private static int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void zenAndKiloCoexistAndDisconnectingKiloLeavesZenIntact() {
        UUID user = user("both");
        UUID zen = provider(user, "OpenCode Zen Free", "OPENCODE_ZEN_FREE");
        UUID kilo = provider(user, "Kilo Gateway Free", "KILO_GATEWAY_FREE");
        assign(user, "KNOWLEDGE_CHAT", zen);
        assign(user, "AGENT", kilo);

        int deleted = repository.deleteByUserAndPreset(user, "KILO_GATEWAY_FREE");

        assertThat(deleted).isOne();
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", kilo)).isZero();
        // Zen 行与其用途绑定不受影响；Kilo 的用途绑定随外键级联清空。
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", zen)).isOne();
        assertThat(count("SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=? AND purpose=?", user, "KNOWLEDGE_CHAT")).isOne();
        assertThat(count("SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=? AND purpose=?", user, "AGENT")).isZero();
    }

    @Test
    void zenDisconnectLeavesKiloIntact() {
        UUID user = user("reverse");
        UUID zen = provider(user, "OpenCode Zen Free", "OPENCODE_ZEN_FREE");
        UUID kilo = provider(user, "Kilo Gateway Free", "KILO_GATEWAY_FREE");
        assign(user, "PLANNING", kilo);

        repository.deleteByUserAndPreset(user, "OPENCODE_ZEN_FREE");

        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", zen)).isZero();
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", kilo)).isOne();
        assertThat(count("SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=?", user)).isOne();
    }

    @Test
    void v65AcceptsKiloPresetAndStillRejectsUnknownPresetCodes() {
        UUID user = user("constraint");
        UUID kilo = provider(user, "Kilo Gateway Free", "KILO_GATEWAY_FREE");
        assertThat(count("SELECT count(*) FROM user_ai_provider WHERE id=?", kilo)).isOne();

        // 同一用户的第二个 Kilo 预设行被 (user_id, preset_code) 唯一索引拒绝。
        assertThatThrownBy(() -> provider(user, "Kilo Gateway Free Again", "KILO_GATEWAY_FREE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 白名单之外的 preset_code 仍被 CHECK 约束拒绝。
        assertThatThrownBy(() -> provider(user, "Rogue Preset", "ROGUE_PRESET"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void restartingOnAnAlreadyMigratedDatabaseExecutesNoMigration() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isNotNull().isPositive();

        var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        assertThat(flyway.info().current()).isNotNull();
        // 重复启动：已迁移的库上不重复执行任何迁移。
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(applied);
    }
}
