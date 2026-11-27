package com.shitulelv.aicollab.knowledge.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V105 / V106 迁移测试（契约 docs/design/p2-rag-contract-2026-11-27.md §5.4；需要 Docker，本机未执行）。
 * <ul>
 *   <li>V105：ai_job 接受三种索引任务类型和 parent_job_id 级联；租借用途接受 RERANK；</li>
 *   <li>V106：历史引用从 document_chunk 回填 document_id / chunk_no / content_hash，
 *       外键去掉后删除 document_chunk 不再级联删除引用；新身份 CHECK 生效。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class P2CitationIdentityMigrationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void v105AndV106MigrateLegacyRowsAndEnforceNewIdentity() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("104").load().migrate();

        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID legacyCitation = UUID.randomUUID();
        String hash = "a".repeat(64);
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'unused','p2')",
                user, "p2-" + user.toString().substring(0, 8));
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'p2',?,?)", project, user, user);
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,"
                + "object_key,status,uploaded_by) VALUES (?,?,'doc','doc.txt','text/plain',1,'fixture/p2','READY',?)",
                doc, project, user);
        jdbc.update("INSERT INTO document_chunk(id,project_id,document_id,chunk_no,content,content_hash,"
                + "embedding_provider,embedding_model,embedding_dimension,embedding,embedding_fingerprint) "
                + "VALUES (?,?,?,7,'synthetic text',?,'test','test',2,'[1,0]',?)",
                chunk, project, doc, hash, "1".repeat(64));
        jdbc.update("INSERT INTO knowledge_session(id,project_id,user_id) VALUES (?,?,?)", session, project, user);
        jdbc.update("INSERT INTO knowledge_message(id,session_id,role,content) VALUES (?,?,'ASSISTANT','a')",
                message, session);
        jdbc.update("INSERT INTO knowledge_citation(id,message_id,chunk_id,rank,similarity,quote_text) "
                + "VALUES (?,?,?,1,0.5,'q')", legacyCitation, message, chunk);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        // V106 回填
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT document_id, chunk_no, content_hash, body_snapshot_id FROM knowledge_citation WHERE id=?",
                legacyCitation);
        assertThat(row.get("document_id")).isEqualTo(doc);
        assertThat(row.get("chunk_no")).isEqualTo(7);
        assertThat(row.get("content_hash")).isEqualTo(hash);
        assertThat(row.get("body_snapshot_id")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid='knowledge_citation'::regclass AND contype='f'
                  AND confrelid='document_chunk'::regclass
                """, Integer.class)).isZero();

        // 新身份：不带 chunk_id 的新引用必须带 chunk_no + content_hash
        jdbc.update("INSERT INTO knowledge_citation(id,message_id,document_id,chunk_no,content_hash,rank,similarity,"
                + "quote_text) VALUES (?,?,?,3,?,2,0.4,'q2')", UUID.randomUUID(), message, doc, hash);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO knowledge_citation(id,message_id,document_id,rank,"
                + "similarity,quote_text) VALUES (?,?,?,3,0.4,'q3')", UUID.randomUUID(), message, doc))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 外键已去掉：删 document_chunk 不再级联删除引用；删文档仍级联
        jdbc.update("DELETE FROM document_chunk WHERE id=?", chunk);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_citation WHERE id=?", Integer.class,
                legacyCitation)).isOne();

        // V105：三种索引任务 + 父子级联 + RERANK 租借用途
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        insertJob(jdbc, parent, project, user, "REINDEX_GENERATION", null, null);
        insertJob(jdbc, child, project, user, "INDEX_DOCUMENT", doc, parent);
        insertJob(jdbc, UUID.randomUUID(), project, user, "DELETE_DOCUMENT_INDEX", UUID.randomUUID(), null);
        assertThatThrownBy(() -> insertJob(jdbc, UUID.randomUUID(), project, user, "INDEX_DOCUMENT", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJob(jdbc, UUID.randomUUID(), project, user, "UNKNOWN_TYPE", doc, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM ai_job WHERE id=?", parent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE id=?", Integer.class, child)).isZero();

        // P2-R4 R4-1：只有 REINDEX_GENERATION 可以不属于项目，也可以没有 created_by
        UUID global = UUID.randomUUID();
        insertJob(jdbc, global, null, null, "REINDEX_GENERATION", null, null);
        assertThat(jdbc.queryForObject("SELECT project_id IS NULL FROM ai_job WHERE id=?", Boolean.class, global))
                .isTrue();
        insertJob(jdbc, UUID.randomUUID(), project, user, "INDEX_DOCUMENT", doc, global);
        for (String type : new String[] {"MEMORY_EXTRACTION", "INDEX_DOCUMENT", "DELETE_DOCUMENT_INDEX"}) {
            assertThatThrownBy(() -> insertJob(jdbc, UUID.randomUUID(), null, user, type, doc, null))
                    .as("job_type=%s 不能没有 project_id", type)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid='ai_job'::regclass AND conname='ck_ai_job_project_bound'",
                Integer.class)).isOne();

        jdbc.update("""
                INSERT INTO ai_credential_lease(id,user_id,project_id,purpose,provider_id,provider_type,model,
                    issued_at,expires_at)
                VALUES (?,?,?,'RERANK',?,'LLAMACPP_RERANK','bge-reranker',now(),now()+interval '1 minute')
                """, UUID.randomUUID(), user, project, UUID.randomUUID());

        jdbc.update("DELETE FROM project_document WHERE id=?", doc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_citation WHERE message_id=?", Integer.class,
                message)).isZero();
    }

    /** 列集合取自 V91；V105 起 project_id 仅 REINDEX_GENERATION 可为空。status 取 ck_ai_job_status 中的 QUEUED。 */
    private static void insertJob(JdbcTemplate jdbc, UUID id, UUID project, UUID user, String type,
                                  UUID documentId, UUID parentJobId) {
        jdbc.update("""
                INSERT INTO ai_job(id,project_id,created_by,job_type,status,payload,document_id,parent_job_id)
                VALUES (?,?,?,?,'QUEUED','{}'::jsonb,?,?)
                """, id, project, user, type, documentId, parentJobId);
    }
}
