package com.shitulelv.aicollab.internal.ai.credential;

import com.shitulelv.aicollab.agent.job.AiJobRepository;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 凭据租借真库测试（契约 §2.4、§6；需要 Docker，本地未执行）：
 * V104 审计行落在 public.ai_credential_lease、不含任何密钥或密文；用户删除后 user_id 置空；
 * 日志（OutputCapture）中搜不到解密后的 key 与密文。
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class CredentialLeaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    /** 明文哨兵：拼接构造。 */
    private static final String SENTINEL = String.join("-", "sk", "lease", "it", "c41e9b07d2a3");

    private static JdbcTemplate jdbc;
    private static ModelSecretCipher cipher;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        cipher = new ModelSecretCipher("test-only-master-key-0123456789");
    }

    @Test
    void auditRowHoldsNoSecretAndLogsNeverShowTheKey(CapturedOutput output) {
        UUID user = user();
        UUID project = project(user);
        String ciphertext = cipher.encrypt(SENTINEL);
        UUID providerId = UUID.randomUUID();
        UserAiProvider provider = new UserAiProvider(providerId, user, "p", ModelProviderType.OPENAI_COMPATIBLE,
                "https://api.example.test", "/v1/chat/completions", ciphertext, "model-it", true, 0.2, 2048,
                EnumSet.of(ModelCapability.CHAT), true, OffsetDateTime.now(), OffsetDateTime.now(), false);
        UserAiProviderService providers = mock(UserAiProviderService.class);
        when(providers.resolve(user, ModelPurpose.KNOWLEDGE_CHAT)).thenReturn(provider);
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);

        CredentialLeaseService service = new CredentialLeaseService(providers, cipher, access,
                new AiJobRepository(jdbc), new CredentialLeaseRepository(jdbc), null, Clock.systemUTC());
        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.QUERY_PLAN, user, project, null, null),
                new InternalAiClaims(user, project, Set.of("QUERY_PLAN"), null, null,
                        Set.of("plan", "credentials:lease", "usage:report")));

        assertThat(grant.apiKey()).isEqualTo(SENTINEL);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM public.ai_credential_lease WHERE id=?",
                grant.leaseId());
        assertThat(row.get("provider_id")).isEqualTo(providerId);
        assertThat(row.get("provider_type")).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(row.get("purpose")).isEqualTo("QUERY_PLAN");
        assertThat(row.values().toString()).doesNotContain(SENTINEL).doesNotContain(ciphertext);
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='ai_credential_lease'
                """, String.class);
        assertThat(columns).noneMatch(name -> name.contains("key") || name.contains("secret"));

        jdbc.update("DELETE FROM project WHERE id=?", project);
        jdbc.update("DELETE FROM app_user WHERE id=?", user);
        Map<String, Object> afterDelete = jdbc.queryForMap(
                "SELECT user_id, project_id FROM ai_credential_lease WHERE id=?", grant.leaseId());
        assertThat(afterDelete.get("user_id")).isNull();
        assertThat(afterDelete.get("project_id")).isNull();

        assertThat(output.getAll()).contains("leaseId=" + grant.leaseId());
        assertThat(output.getAll()).doesNotContain(SENTINEL).doesNotContain(ciphertext);
    }

    private static UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "lease-" + id.toString().substring(0, 8), "test-only-hash", "Lease");
        return id;
    }

    private static UUID project(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,name,description,owner_id,created_by,start_date,due_date)
                VALUES (?,?,?,?,?,DATE '2026-11-01',DATE '2026-12-31')
                """, id, "租借项目", "描述", owner, owner);
        return id;
    }
}
