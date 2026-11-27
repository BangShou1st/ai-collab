package com.shitulelv.aicollab.agent.job;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V105 {@code index_phase} 与 {@link AiJobRecord} / {@link AiJobRepository} 单元测试（不依赖 Docker）。
 */
class AiJobRepositoryIndexTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DOC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GEN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PARENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 27, 8, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void recordConstructorsPreserveP1And21ArgSignaturesAndSupportIndexPhase() {
        AiJobRecord p1 = new AiJobRecord(JOB_ID, PROJECT_ID, AiJobTypes.MEMORY_EXTRACTION, AiJobStatus.QUEUED,
                1, "{}", 3, 0, 0L, null, null, null, null, null, null, NOW, NOW, 0);
        assertThat(p1.documentId()).isNull();
        assertThat(p1.generationId()).isNull();
        assertThat(p1.parentJobId()).isNull();
        assertThat(p1.indexPhase()).isNull();
        assertThat(p1.indexTask()).isFalse();

        AiJobRecord v21 = new AiJobRecord(JOB_ID, PROJECT_ID, AiJobTypes.INDEX_DOCUMENT, AiJobStatus.QUEUED,
                1, "{}", 3, 0, 0L, null, null, null, null, null, null, NOW, NOW, 0,
                DOC_ID, GEN_ID, PARENT_ID);
        assertThat(v21.documentId()).isEqualTo(DOC_ID);
        assertThat(v21.generationId()).isEqualTo(GEN_ID);
        assertThat(v21.parentJobId()).isEqualTo(PARENT_ID);
        assertThat(v21.indexPhase()).isNull();
        assertThat(v21.indexTask()).isTrue();

        AiJobRecord full = new AiJobRecord(JOB_ID, null, AiJobTypes.REINDEX_GENERATION, AiJobStatus.SUCCEEDED,
                1, "{}", 3, 1, 0L, null, null, null, null, null, null, NOW, NOW, 2,
                null, GEN_ID, null, AiJobTypes.INDEX_PHASE_FANNED_OUT);
        assertThat(full.projectId()).isNull();
        assertThat(full.generationId()).isEqualTo(GEN_ID);
        assertThat(full.indexPhase()).isEqualTo(AiJobTypes.INDEX_PHASE_FANNED_OUT);
        assertThat(full.indexTask()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowMapperReadsIndexPhaseAndFallsBackToNullOnV104ResultSet() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiJobRepository repository = new AiJobRepository(jdbc);

        when(jdbc.query(contains("index_phase='FANNED_OUT'"), any(RowMapper.class), anyInt()))
                .thenAnswer(invocation -> {
                    RowMapper<AiJobRecord> mapper = invocation.getArgument(1);
                    return List.of(
                            mapper.mapRow(mockResultSet(true, "FANNED_OUT"), 0),
                            mapper.mapRow(mockResultSet(false, null), 1));
                });

        List<AiJobRecord> rows = repository.findReindexFannedOut(50);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).indexPhase()).isEqualTo("FANNED_OUT");
        assertThat(rows.get(0).generationId()).isEqualTo(GEN_ID);
        assertThat(rows.get(1).indexPhase()).isNull();
        assertThat(rows.get(1).documentId()).isNull();
        assertThat(rows.get(1).generationId()).isNull();
        assertThat(rows.get(1).parentJobId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void casUpdateIndexPhaseAndCasCompleteReindexPassExpectedParameters() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiJobRepository repository = new AiJobRepository(jdbc);
        AiJobRecord current = new AiJobRecord(JOB_ID, null, AiJobTypes.REINDEX_GENERATION, AiJobStatus.RUNNING,
                1, "{}", 3, 1, 2L, UUID.randomUUID(), NOW.plusSeconds(60), null, null, null, null, NOW, NOW, 3,
                null, null, null, null);
        AiJobRecord fannedOut = new AiJobRecord(JOB_ID, null, AiJobTypes.REINDEX_GENERATION, AiJobStatus.SUCCEEDED,
                1, "{}", 3, 1, 2L, current.leaseToken(), null, null, null, null, null, NOW, NOW, 4,
                null, GEN_ID, null, AiJobTypes.INDEX_PHASE_FANNED_OUT);
        AiJobRecord activated = new AiJobRecord(JOB_ID, null, AiJobTypes.REINDEX_GENERATION, AiJobStatus.SUCCEEDED,
                1, "{}", 3, 1, 2L, current.leaseToken(), null, null, null, null, null, NOW, NOW, 5,
                null, GEN_ID, null, AiJobTypes.INDEX_PHASE_ACTIVATED);

        when(jdbc.query(contains("SET status='SUCCEEDED'"), any(RowMapper.class),
                eq(GEN_ID), eq("FANNED_OUT"), eq(NOW), eq(JOB_ID), eq(3), eq("RUNNING"), eq(2L)))
                .thenReturn(List.of(fannedOut));
        when(jdbc.query(contains("SET index_phase=?"), any(RowMapper.class),
                eq("ACTIVATED"), eq(NOW), eq(JOB_ID), eq(4), eq("FANNED_OUT")))
                .thenReturn(List.of(activated));

        Optional<AiJobRecord> completed = repository.casCompleteReindex(current, 2L, GEN_ID, "FANNED_OUT", NOW);
        Optional<AiJobRecord> updated = repository.casUpdateIndexPhase(JOB_ID, 4, "FANNED_OUT", "ACTIVATED", NOW);

        assertThat(completed).contains(fannedOut);
        assertThat(updated).contains(activated);
    }

    private static ResultSet mockResultSet(boolean includeV105Columns, String indexPhase) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        List<String> labels = includeV105Columns
                ? List.of("id", "project_id", "job_type", "status", "schema_version", "payload", "max_attempts",
                        "attempt_count", "lease_epoch", "lease_token", "lease_expires_at", "next_attempt_at",
                        "deadline_at", "last_error_code", "created_by", "created_at", "updated_at", "version",
                        "document_id", "generation_id", "parent_job_id", "index_phase")
                : List.of("id", "project_id", "job_type", "status", "schema_version", "payload", "max_attempts",
                        "attempt_count", "lease_epoch", "lease_token", "lease_expires_at", "next_attempt_at",
                        "deadline_at", "last_error_code", "created_by", "created_at", "updated_at", "version");
        when(meta.getColumnCount()).thenReturn(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            int col = i + 1;
            when(meta.getColumnLabel(col)).thenReturn(labels.get(i));
        }
        when(rs.getObject("id", UUID.class)).thenReturn(JOB_ID);
        when(rs.getObject("project_id", UUID.class)).thenReturn(null);
        when(rs.getString("job_type")).thenReturn(AiJobTypes.REINDEX_GENERATION);
        when(rs.getString("status")).thenReturn("SUCCEEDED");
        when(rs.getInt("schema_version")).thenReturn(1);
        when(rs.getString("payload")).thenReturn("{}");
        when(rs.getInt("max_attempts")).thenReturn(3);
        when(rs.getInt("attempt_count")).thenReturn(1);
        when(rs.getLong("lease_epoch")).thenReturn(0L);
        when(rs.getObject("lease_token", UUID.class)).thenReturn(null);
        when(rs.getObject("lease_expires_at", OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject("next_attempt_at", OffsetDateTime.class)).thenReturn(null);
        when(rs.getObject("deadline_at", OffsetDateTime.class)).thenReturn(null);
        when(rs.getString("last_error_code")).thenReturn(null);
        when(rs.getObject("created_by", UUID.class)).thenReturn(null);
        when(rs.getObject("created_at", OffsetDateTime.class)).thenReturn(NOW);
        when(rs.getObject("updated_at", OffsetDateTime.class)).thenReturn(NOW);
        when(rs.getInt("version")).thenReturn(1);
        if (includeV105Columns) {
            when(rs.getObject(19, UUID.class)).thenReturn(null);
            when(rs.getObject(20, UUID.class)).thenReturn(GEN_ID);
            when(rs.getObject(21, UUID.class)).thenReturn(null);
            when(rs.getString(22)).thenReturn(indexPhase);
        }
        return rs;
    }
}
