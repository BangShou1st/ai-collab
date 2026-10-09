package com.shitulelv.aicollab.infrastructure.ai.embedding;

import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingBatch;
import com.shitulelv.aicollab.common.exception.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers(disabledWithoutDocker=true)
class EmbeddingGenerationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    SystemEmbeddingConfigRepository configs;
    ProjectEmbeddingGateway gateway;
    EmbeddingIndexService indexes;
    TransactionTemplate tx;
    SystemEmbeddingConfig active,candidate;
    UUID document,project;
    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds); manager=new DataSourceTransactionManager(ds);
    }
    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE app_user,system_embedding_config,embedding_index_generation CASCADE");
        tx=new TransactionTemplate(manager); configs=new SystemEmbeddingConfigRepository(jdbc);
        gateway=mock(ProjectEmbeddingGateway.class);
        indexes=new EmbeddingIndexService(jdbc,configs,gateway,mock(ApplicationEventPublisher.class),manager);
        active=config("old",true); candidate=config("new",false);
        configs.save(active); configs.save(candidate);
        UUID user=UUID.randomUUID(); project=UUID.randomUUID(); document=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'hash','Probe')",user,user.toString());
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'IndexProbe',?,?)",project,user,user);
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,chunk_count,uploaded_by,indexed_at) VALUES (?,?,'d','d.txt','text/plain',5,'probe','READY',1,?,now())",document,project,user);
        jdbc.update("INSERT INTO document_chunk(id,project_id,document_id,chunk_no,content,content_hash,token_estimate,metadata,embedding_provider,embedding_model,embedding_dimension,embedding_fingerprint,embedding) VALUES (?,?,?,0,'synthetic content','hash',2,'{}','OLLAMA','old',3,?,'[1,0,0]')",UUID.randomUUID(),project,document,active.fingerprint());
        when(gateway.embedWithConfig(any(),anyList(),any())).thenReturn(new EmbeddingBatch("OLLAMA","new",3,List.of(List.of(1d,0d,0d)),candidate.fingerprint()));
    }
    @AfterEach void close() { indexes.close(); }
    private SystemEmbeddingConfig config(String model,boolean enabled) {
        var now=OffsetDateTime.now();
        return new SystemEmbeddingConfig(UUID.randomUUID(),"OLLAMA","http://127.0.0.1:11434","/v1/embeddings",null,model,3,16,EmbeddingFingerprints.fingerprint("OLLAMA",model,3),enabled,now,now);
    }
    private UUID build() {
        Integer total=tx.execute(status -> indexes.start(candidate));
        assertThat(total).isEqualTo(1);
        UUID id=jdbc.queryForObject("SELECT id FROM embedding_index_generation",UUID.class);
        indexes.build(id); return id;
    }
    @Test void candidateBuildKeepsOldActiveAndExplicitActivationSwitchesSnapshot() {
        UUID id=build();
        assertThat(configs.findActive().orElseThrow().id()).isEqualTo(active.id());
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_index_generation WHERE id=?",String.class,id)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk",Integer.class)).isEqualTo(2);
        tx.executeWithoutResult(status -> indexes.activate(id));
        var selected=configs.findActive().orElseThrow();
        assertThat(selected.id()).isEqualTo(candidate.id()); assertThat(selected.generationId()).isEqualTo(id);
        assertThat(selected.fingerprint()).isEqualTo(candidate.fingerprint());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> indexes.discard(id))).isInstanceOf(BusinessException.class);
    }
    @Test void failedBuildKeepsOldActiveAndCanBeDiscarded() {
        when(gateway.embedWithConfig(any(),anyList(),any())).thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED));
        UUID id=build();
        assertThat(configs.findActive().orElseThrow().id()).isEqualTo(active.id());
        assertThat(jdbc.queryForObject("SELECT state FROM embedding_index_generation WHERE id=?",String.class,id)).isEqualTo("FAILED");
        tx.executeWithoutResult(status -> indexes.discard(id));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk",Integer.class)).isEqualTo(1);
        verify(gateway,times(1)).embedWithConfig(any(),anyList(),any());
    }
    @Test void changedDocumentRejectsActivation() {
        UUID id=build(); jdbc.update("UPDATE project_document SET version=version+1 WHERE id=?",document);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> indexes.activate(id))).isInstanceOf(BusinessException.class);
        assertThat(configs.findActive().orElseThrow().id()).isEqualTo(active.id());
    }
    @Test void changedSemanticConfigRejectsActivation() {
        UUID id=build(); jdbc.update("UPDATE system_embedding_config SET fingerprint='changed' WHERE id=?",active.id());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> indexes.activate(id))).isInstanceOf(BusinessException.class);
    }
    @Test void abandonedBuildBecomesFailedWithoutSwitchingActive() {
        UUID id=build(); jdbc.update("UPDATE embedding_index_generation SET state='BUILDING',updated_at=now()-interval '11 minutes' WHERE id=?",id);
        indexes.recoverAbandonedBuilds();
        assertThat(jdbc.queryForObject("SELECT error_code FROM embedding_index_generation WHERE id=?",String.class,id)).isEqualTo("INDEX_BUILD_INTERRUPTED");
        assertThat(configs.findActive().orElseThrow().id()).isEqualTo(active.id());
    }
}
