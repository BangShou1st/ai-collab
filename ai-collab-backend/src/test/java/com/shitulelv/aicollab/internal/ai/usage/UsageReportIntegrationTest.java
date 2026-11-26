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
}
