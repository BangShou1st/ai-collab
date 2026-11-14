package com.shitulelv.aicollab.infrastructure.ai.user;

import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V102 removes product presets while preserving custom providers and shared-provider routing. */
@Testcontainers(disabledWithoutDocker = true)
class V102LegacyProviderMigrationPostgresTest {
    private static final String LEGACY_COLUMN = "preset" + "_code";
    private static final String LEGACY_CODE_A = "OPENCODE" + "_ZEN_FREE";
    private static final String LEGACY_CODE_B = "KILO" + "_GATEWAY_FREE";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void v102DropsPresetRowsAndColumnWithoutAffectingCustomOrSharedProviders() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("101").load().migrate();

        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "v102-" + user.toString().substring(0, 8), "test-only-hash", "V102");
        UUID legacyA = provider(jdbc, user, "Legacy provider A", LEGACY_CODE_A);
        UUID legacyB = provider(jdbc, user, "Legacy provider B", LEGACY_CODE_B);
        UUID custom = provider(jdbc, user, "Custom", null);
        jdbc.update("""
                INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id,is_shared)
                VALUES (?,'AGENT',?,FALSE)
                """, user, legacyA);
        jdbc.update("""
                INSERT INTO user_ai_provider_probe_result(
                    provider_id,probe_mode,status,model_name,tested_config_updated_at)
                VALUES (?,'CONNECTIVITY','PASS','test-model',CURRENT_TIMESTAMP)
                """, legacyB);

        var result = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();

        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='user_ai_provider' AND column_name=?",
                Integer.class, LEGACY_COLUMN)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_ai_provider WHERE id IN (?,?)", Integer.class, legacyA, legacyB)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_ai_provider WHERE id=?", Integer.class, custom)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_model_purpose_assignment WHERE user_id=? AND is_shared=FALSE",
                Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_ai_provider_probe_result WHERE provider_id=?", Integer.class, legacyB))
                .isZero();

        var repository = new UserAiProviderRepository(jdbc);
        UserAiProvider personal = repository.findByIdAndUserId(custom, user).orElseThrow();
        assertThat(personal.shared()).isFalse();
        UserAiProvider saved = repository.save(new UserAiProvider(
                UUID.randomUUID(), user, "Custom after V102",
                personal.providerType(), "https://api.example.com", "/v1/chat/completions",
                "encrypted-key", "custom-model", true, 0.2, 1200,
                EnumSet.of(ModelCapability.CHAT), false, OffsetDateTime.now(), OffsetDateTime.now(), false));
        assertThat(saved.shared()).isFalse();

        UUID shared = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shared_ai_provider(
                    id,source_provider_id,publisher_user_id,name,provider_type,base_url,api_path,
                    encrypted_api_key,model_name,enabled,status,temperature,max_output_tokens,capabilities)
                VALUES (?,?,?,?,'OPENAI_COMPATIBLE','https://shared.example.com','/v1/chat/completions',
                    'encrypted-shared-key','shared-model',TRUE,'PUBLISHED',0.2,1200,'CHAT,STREAMING')
                """, shared, custom, user, "Shared after V102");
        jdbc.update("""
                INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id,is_shared)
                VALUES (?,'AGENT',?,TRUE)
                """, user, shared);

        UserAiProvider runtime = repository.findAssigned(user, ModelPurpose.AGENT).orElseThrow();
        assertThat(runtime.id()).isEqualTo(shared);
        assertThat(runtime.shared()).isTrue();
        assertThat(runtime.enabled()).isTrue();
    }

    private static UUID provider(JdbcTemplate jdbc, UUID user, String name, String legacyCode) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_ai_provider("
                        + "id,user_id,name,provider_type,base_url,api_path,encrypted_api_key,model_name,"
                        + "enabled,temperature,max_output_tokens,capabilities,is_default," + LEGACY_COLUMN + ") "
                        + "VALUES (?,?,?,'OPENAI_COMPATIBLE','https://api.example.com','/v1/chat/completions',"
                        + "'encrypted-key','test-model',TRUE,0.2,1200,'CHAT',FALSE,?)",
                id, user, name, legacyCode);
        return id;
    }
}
