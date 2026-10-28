package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual final persistence, no model calls. Available identity is not semantic support. */
@Testcontainers(disabledWithoutDocker = true)
class AgentFinalCitationContractPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static JdbcTemplate jdbc;
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static AgentRepository repository;
    static final List<Map<String,Object>> QUERY_PLANS=new ArrayList<>();
    static int projectionQueries;
    UUID user,project,document,chunk,run;
    AgentCitation citation;

    @BeforeAll static void migrate() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds) {
            @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
                List<T> result=super.queryForList(sql,type,args);
                if(sql.contains("SELECT DISTINCT ON (c.value->>'chunkId')")) {
                    projectionQueries++;
                    assertThat(result).hasSizeLessThanOrEqualTo(50);
                    String plan=super.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+sql,String.class,args);
                    QUERY_PLANS.add(Map.of("sql",sql,"parameterCount",args.length,
                            "returnedRows",result.size(),"plan",plan));
                }
                return result;
            }
        };
        repository=new AgentRepository(jdbc,JSON,new AgentRunEventRecorder(jdbc,JSON));
    }
    @BeforeEach void fixture() {
        projectionQueries=0;
        user=UUID.randomUUID();project=UUID.randomUUID();document=UUID.randomUUID();chunk=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",user,"t"+user,"test-only","test");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'citation contract',?,?)",project,user,user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",project,user);
        jdbc.update("""
                INSERT INTO project_document(id,project_id,original_filename,display_name,mime_type,size_bytes,object_key,status,uploaded_by)
                VALUES (?,?,'contract.md','contract.md','text/markdown',1,?,'READY',?)
                """,document,project,document.toString(),user);
        jdbc.update("""
                INSERT INTO document_chunk(id,project_id,document_id,chunk_no,content,content_hash,
                  embedding_provider,embedding_model,embedding_dimension,embedding,embedding_fingerprint)
                VALUES (?,?,?,0,'原始合法摘录',?,'TEST','TEST',3,'[1,0,0]'::vector,'test-fingerprint')
                """,chunk,project,document,"a".repeat(64));
        citation=new AgentCitation(document,chunk,"contract.md","section",null,"原始合法摘录",0.9);
        var session=repository.createSession(project,user,"contract");
        run=repository.createRun(project,session.id(),user,"audit",false,null,null).id();
        jdbc.update("UPDATE agent_run SET status='RUNNING' WHERE id=?",run);
    }
    @AfterEach void boundedFinalProjection() {
        assertThat(projectionQueries).isLessThanOrEqualTo(1);
    }
    @AfterAll static void savePlans() throws Exception {
        var file=java.nio.file.Path.of(System.getProperty("p8.fix.outputDir","target/p8-rag-quality-fix-01"),"citation-sql-plans.json");
        java.nio.file.Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),QUERY_PLANS);
    }
    void evidence(String type,String status,AgentCitation value) throws Exception {
        var output=JSON.createObjectNode().put("status",status);
        output.set("citations",JSON.valueToTree(List.of(value)));
        jdbc.update("INSERT INTO agent_step(run_id,sequence_no,type,reason,output_json) VALUES (?,1,?,'TOOL_SUCCESS',?::jsonb)",run,type,output.toString());
    }
    JsonNode finish(List<AgentCitation> handed) throws Exception {
        String body="文档摘录含 [S999]；没有定义 Agent 编号映射。";
        repository.recordFinal(repository.findRun(project,run).orElseThrow(),body,handed);
        var saved=jdbc.queryForMap("SELECT content,citations_json::text citations FROM agent_message WHERE run_id=? AND role='ASSISTANT'",run);
        assertThat(saved.get("content")).isEqualTo(body);
        return JSON.readTree(saved.get("citations").toString());
    }
    @Test void availableButUncitedSourceRemainsAvailableWithoutRewritingAnswer() throws Exception {
        evidence("TOOL_CALL_COMPLETED","SUCCEEDED",citation);
        assertThat(finish(List.of())).hasSize(1);
    }
    @Test void unknownHandedIdentityCannotCreateSourceCard() throws Exception {
        var fake=new AgentCitation(document,UUID.randomUUID(),"fake","",null,"fake",1);
        assertThat(finish(List.of(fake))).isEmpty();
    }
    @Test void validIdentityWithoutPersistedReadCannotCreateSourceCard() throws Exception {
        assertThat(finish(List.of(citation))).isEmpty();
    }
    @Test void delegatedSourceBecomingStaleBeforeParentFinalIsExcluded() throws Exception {
        evidence("DELEGATION_COMPLETED","SUCCEEDED",citation);
        jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?",document);
        assertThat(finish(List.of(citation))).isEmpty();
    }
    @Test void validDelegatedIdentityKeepsPersistedQuoteRatherThanHandedText() throws Exception {
        evidence("DELEGATION_COMPLETED","SUCCEEDED",citation);
        var altered=new AgentCitation(document,chunk,"fake","",null,"不是原始摘录",1);
        var result=finish(List.of(altered));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("quote").asText()).isEqualTo(citation.quote());
    }
    @Test void partialFailedDelegationKeepsAcquiredSourceWithoutClaimingSemanticSupport() throws Exception {
        evidence("DELEGATION_COMPLETED","FAILED",citation);
        // resumeParent only transfers sources from successful tool reads, even if the child
        // itself later fails. Available material is not a verified final claim.
        assertThat(finish(List.of(citation))).hasSize(1);
    }
    @Test void failedToolCannotSupplySuccessfulSource() throws Exception {
        evidence("TOOL_CALL_COMPLETED","FAILED",citation);
        assertThat(finish(List.of())).isEmpty();
    }
    @Test void delegatedSourceFromAnotherProjectIsExcluded() throws Exception {
        evidence("DELEGATION_COMPLETED","SUCCEEDED",citation);
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'other project',?,?)",other,user,user);
        jdbc.update("UPDATE project_document SET project_id=? WHERE id=?",other,document);
        assertThat(finish(List.of(citation))).isEmpty();
    }
    @Test void deletedDelegatedChunkIsExcluded() throws Exception {
        evidence("DELEGATION_COMPLETED","SUCCEEDED",citation);
        jdbc.update("DELETE FROM document_chunk WHERE id=?",chunk);
        assertThat(finish(List.of(citation))).isEmpty();
    }
    @Test void mismatchedDocumentIdentityIsExcluded() throws Exception {
        evidence("DELEGATION_COMPLETED","SUCCEEDED",citation);
        var mismatch=new AgentCitation(UUID.randomUUID(),chunk,"fake","",null,"fake",1);
        assertThat(finish(List.of(mismatch))).isEmpty();
    }
    @Test void repeatedDirectAndDelegatedSourceRetainsSingleIdentity() throws Exception {
        evidence("TOOL_CALL_COMPLETED","SUCCEEDED",citation);
        assertThat(finish(List.of(citation,citation))).hasSize(1);
    }
}
