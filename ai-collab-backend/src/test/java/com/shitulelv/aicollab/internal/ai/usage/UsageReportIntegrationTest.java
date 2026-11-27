package com.shitulelv.aicollab.internal.ai.usage;

import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.credential.CredentialLeaseRepository;
import com.shitulelv.aicollab.internal.ai.credential.LeasePurpose;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量回报真库测试（契约 §3、§6；需要 Docker，本地未执行）：同一 callId 重复上报只落一行且都返回 accepted；
 * caller_type=PYTHON、feature=purpose、request_id=callId、provider 回填自 lease；CANCELED 状态与小写 errorCode 能落库。
 */
@Testcontainers(disabledWithoutDocker = true)
class UsageReportIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static JdbcTemplate jdbc;
    private static UsageReportService service;
    private static CredentialLeaseRepository leases;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        leases = new CredentialLeaseRepository(jdbc);
        service = new UsageReportService(leases, new UsageReportRepository(jdbc), new SimpleMeterRegistry());
    }

    @Test
    void duplicateCallIdIsIdempotentAndColumnsAreBackfilled() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "usage-" + user.toString().substring(0, 8), "test-only-hash", "Usage");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,name,description,owner_id,created_by,start_date,due_date)
                VALUES (?,?,?,?,?,DATE '2026-11-01',DATE '2026-12-31')
                """, project, "用量项目", "描述", user, user);
        UUID leaseId = UUID.randomUUID();
        OffsetDateTime issued = OffsetDateTime.now(ZoneOffset.UTC);
        leases.insert(new CredentialLeaseRepository.LeaseRecord(leaseId, user, project, LeasePurpose.QUERY_PLAN,
                UUID.randomUUID(), "GEMINI", "model-it", null, null, issued, issued.plusSeconds(300)));
        InternalAiClaims claims = new InternalAiClaims(user, project, Set.of("QUERY_PLAN"), null, null,
                Set.of("usage:report"));
        String callId = UUID.randomUUID().toString();
        UsageReportService.Item item = new UsageReportService.Item(callId, leaseId.toString(), "QUERY_PLAN",
                "model-it", 10L, 20L, 30L, "CANCELED", "provider_timeout");

        List<UsageReportService.Result> first = service.report(List.of(item), claims);
        List<UsageReportService.Result> again = service.report(List.of(item, item), claims);

        assertThat(first).singleElement().satisfies(result -> assertThat(result.accepted()).isTrue());
        assertThat(again).hasSize(2).allSatisfy(result -> assertThat(result.accepted()).isTrue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.ai_call_log WHERE call_id=?::uuid",
                Integer.class, callId)).isOne();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM public.ai_call_log WHERE call_id=?::uuid", callId);
        assertThat(row.get("caller_type")).isEqualTo("PYTHON");
        assertThat(row.get("feature")).isEqualTo("QUERY_PLAN");
        assertThat(row.get("provider")).isEqualTo("GEMINI");
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(row.get("error_code")).isEqualTo("provider_timeout");
        assertThat(row.get("lease_id")).isEqualTo(leaseId);
        assertThat(row.get("request_id").toString()).isEqualTo(callId);
        assertThat(row.get("prompt_tokens")).isEqualTo(10);
        assertThat(row.get("completion_tokens")).isEqualTo(20);
    }

    @Test
    void systemEmbeddingTaskAcceptsNullUserIdAndNullProjectIdAgainstPostgres() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "sys-" + user.toString().substring(0, 8), "test-only-hash", "SystemUsage");
        UUID project = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id,name,description,owner_id,created_by,start_date,due_date)
                VALUES (?,?,?,?,?,DATE '2026-11-01',DATE '2026-12-31')
                """, project, "系统索引项目", "描述", user, user);

        OffsetDateTime issued = OffsetDateTime.now(ZoneOffset.UTC);
        UUID indexTaskId = UUID.randomUUID();
        UUID indexLeaseId = UUID.randomUUID();
        leases.insert(new CredentialLeaseRepository.LeaseRecord(indexLeaseId, null, project, LeasePurpose.EMBEDDING,
                UUID.randomUUID(), "OLLAMA", "qwen3-embedding:0.6b", indexTaskId, null,
                issued, issued.plusSeconds(300)));

        UUID reindexTaskId = UUID.randomUUID();
        UUID reindexLeaseId = UUID.randomUUID();
        leases.insert(new CredentialLeaseRepository.LeaseRecord(reindexLeaseId, null, null, LeasePurpose.EMBEDDING,
                UUID.randomUUID(), "OLLAMA", "qwen3-embedding:0.6b", reindexTaskId, null,
                issued, issued.plusSeconds(300)));

        String indexCallId = UUID.randomUUID().toString();
        InternalAiClaims indexClaims = new InternalAiClaims(
                null, project, Set.of("EMBEDDING"), indexTaskId, 0L, Set.of("usage:report"));
        List<UsageReportService.Result> indexRes = service.report(List.of(new UsageReportService.Item(
                indexCallId, indexLeaseId.toString(), "EMBEDDING", "qwen3-embedding:0.6b",
                30L, 0L, 12L, "SUCCESS", null)), indexClaims);
        assertThat(indexRes).singleElement().satisfies(r -> assertThat(r.accepted()).isTrue());

        String reindexCallId = UUID.randomUUID().toString();
        InternalAiClaims reindexClaims = new InternalAiClaims(
                null, null, Set.of("EMBEDDING"), reindexTaskId, 0L, Set.of("usage:report"));
        List<UsageReportService.Result> reindexRes = service.report(List.of(new UsageReportService.Item(
                reindexCallId, reindexLeaseId.toString(), "EMBEDDING", "qwen3-embedding:0.6b",
                60L, 0L, 25L, "SUCCESS", null)), reindexClaims);
        assertThat(reindexRes).singleElement().satisfies(r -> assertThat(r.accepted()).isTrue());

        Map<String, Object> indexRow = jdbc.queryForMap(
                "SELECT user_id, project_id, feature FROM public.ai_call_log WHERE call_id=?::uuid", indexCallId);
        assertThat(indexRow.get("user_id")).isNull();
        assertThat(indexRow.get("project_id")).isEqualTo(project);
        assertThat(indexRow.get("feature")).isEqualTo("EMBEDDING");

        Map<String, Object> reindexRow = jdbc.queryForMap(
                "SELECT user_id, project_id, feature FROM public.ai_call_log WHERE call_id=?::uuid", reindexCallId);
        assertThat(reindexRow.get("user_id")).isNull();
        assertThat(reindexRow.get("project_id")).isNull();
        assertThat(reindexRow.get("feature")).isEqualTo("EMBEDDING");
    }
}
