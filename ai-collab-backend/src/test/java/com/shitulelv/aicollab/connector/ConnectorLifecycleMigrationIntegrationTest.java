package com.shitulelv.aicollab.connector;

import com.shitulelv.aicollab.connector.application.ConnectorCleanupService;
import com.shitulelv.aicollab.connector.application.ConnectorEventProcessor;
import com.shitulelv.aicollab.connector.application.ConnectorConnectionService;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorEventCredentialRepository;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorConnectionMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorSourceMapper;
import com.shitulelv.aicollab.connector.infrastructure.persistence.ConnectorRows;
import com.shitulelv.aicollab.connector.infrastructure.feishu.FeishuClient;
import com.shitulelv.aicollab.user.service.UserService;
import com.shitulelv.aicollab.connector.application.ConnectorDeletionReplayService;
import com.shitulelv.aicollab.agent.application.AgentHistoryVisibilityService;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.connector.application.ConnectorVisibilityService;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorCleanupRepository;
import com.shitulelv.aicollab.connector.infrastructure.repository.ConnectorOAuthRepository;
import com.shitulelv.aicollab.connector.infrastructure.repository.JdbcConnectorOAuthRepository;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentVisibilityRepository;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.document.infrastructure.mapper.DocumentMapper;
import com.shitulelv.aicollab.document.domain.model.DocumentStatus;
import com.shitulelv.aicollab.document.application.service.StorageAuthorizationService;
import com.shitulelv.aicollab.infrastructure.storage.MinioProperties;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.knowledge.infrastructure.mapper.KnowledgeMessageMapper;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeVisibilityGuard;
import com.shitulelv.aicollab.project.infrastructure.mapper.AuditLogMapper;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectDashboardMapper;
import com.shitulelv.aicollab.notification.infrastructure.mapper.NotificationMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Migration and deletion exercise only an isolated, disposable PostgreSQL container. */
@Testcontainers(disabledWithoutDocker = true)
class ConnectorLifecycleMigrationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static DataSource dataSource;

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return dataSource; }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ConnectorOAuthRepository grants(JdbcTemplate jdbc) { return new JdbcConnectorOAuthRepository(jdbc); }
        @Bean ConnectorVisibilityService visibility(JdbcTemplate jdbc) { return new ConnectorVisibilityService(jdbc); }
        @Bean ConnectorCleanupRepository cleanup(JdbcTemplate jdbc) { return new ConnectorCleanupRepository(jdbc); }
        @Bean DocumentVisibilityRepository documents(JdbcTemplate jdbc) { return new DocumentVisibilityRepository(jdbc); }
        @Bean KnowledgeVisibilityGuard guard(JdbcTemplate jdbc, DocumentVisibilityRepository documents) { return new KnowledgeVisibilityGuard(jdbc, documents); }
        @Bean AgentHistoryVisibilityService agentHistory(JdbcTemplate jdbc) { return new AgentHistoryVisibilityService(jdbc); }
        @Bean ConnectorDeletionReplayService replay(JdbcTemplate jdbc, ConnectorVisibilityService visibility) {
            return new ConnectorDeletionReplayService(jdbc, visibility, true);
        }
        @Bean ConnectorEventProcessor events(JdbcTemplate jdbc, ConnectorVisibilityService visibility) {
            return new ConnectorEventProcessor(jdbc, visibility);
        }
        @Bean ConnectorEventCredentialRepository eventCredentials(JdbcTemplate jdbc) { return new ConnectorEventCredentialRepository(jdbc); }
        @Bean ConnectorConnectionMapper connections() { return mock(ConnectorConnectionMapper.class); }
        @Bean ConnectorConnectionService connectionService(ConnectorConnectionMapper connections,
                ConnectorEventCredentialRepository credentials, ConnectorVisibilityService visibility) {
            return new ConnectorConnectionService(connections,mock(ConnectorSourceMapper.class),mock(FeishuClient.class),
                    new ModelSecretCipher("synthetic-test-master-key-only"),mock(UserService.class),credentials,visibility);
        }
    }

    @Test
    void upgradeFencesRefreshAndBlocksBeforeRetriedCleanup() throws Exception {
        dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("69").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), otherProject = UUID.randomUUID();
        UUID connection = UUID.randomUUID(), source = UUID.randomUUID(), doc = UUID.randomUUID(), otherDoc = UUID.randomUUID();
        UUID external = UUID.randomUUID(), session = UUID.randomUUID(), message = UUID.randomUUID();
        UUID agentSession = UUID.randomUUID();
        UUID agentMemory = UUID.randomUUID();
        String key = "projects/" + project + "/documents/" + doc + "/source.txt";
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,'synthetic-user','unused','test')", user);
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'one',?,?),(?,'two',?,?)", project,user,user,otherProject,user,user);
        jdbc.update("INSERT INTO connector_connection(id,name,app_id,app_secret_cipher,created_by) VALUES (?,'test','synthetic-app','cipher',?)", connection,user);
        jdbc.update("INSERT INTO connector_source(id,project_id,connection_id,folder_token,name,authorization_declared_by,authorization_declared_at) VALUES (?,?,?,'folder','source',?,now())",source,project,connection,user);
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) VALUES (?,?,'doc','doc.txt','text/plain',1,?,'READY',?),(?,?,'other','other.txt','text/plain',1,?,'READY',?)",
                doc,project,key,user,otherDoc,otherProject,"projects/"+otherProject+"/documents/"+otherDoc+"/source.txt",user);
        jdbc.update("INSERT INTO external_document(id,source_id,external_key,doc_type,document_id) VALUES (?,?,'item','docx',?)", external,source,doc);
        jdbc.update("UPDATE external_document SET title='synthetic confidential title',source_url='https://example.invalid/old',content_hash=?,latest_modify_time=123 WHERE id=?","2".repeat(64),external);
        jdbc.update("INSERT INTO external_document_revision(id,external_document_id,revision_no,latest_modify_time,content_hash,document_id,status) VALUES (?,?,1,123,?,?,'ACTIVE')",UUID.randomUUID(),external,"2".repeat(64),doc);
        jdbc.update("INSERT INTO document_chunk(id,project_id,document_id,chunk_no,content,content_hash,embedding_provider,embedding_model,embedding_dimension,embedding,embedding_fingerprint) VALUES (?,?,?,0,'synthetic text',?,'test','test',2,'[1,0]',?)",UUID.randomUUID(),project,doc,"0".repeat(64),"1".repeat(64));
        jdbc.update("INSERT INTO document_body(document_id,snapshot_id,original_content_hash,parse_version) VALUES (?,?,?,'synthetic')",doc,UUID.randomUUID(),"0".repeat(64));
        jdbc.update("INSERT INTO document_body_chunk(id,document_id,chunk_no,content,content_hash) VALUES (?,?,0,'synthetic body',?)",UUID.randomUUID(),doc,"0".repeat(64));
        jdbc.update("INSERT INTO knowledge_session(id,project_id,user_id) VALUES (?,?,?)",session,project,user);
        jdbc.update("INSERT INTO knowledge_message(id,session_id,role,content) VALUES (?,?,'ASSISTANT','previous synthetic answer')",message,session);
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title) VALUES (?,?,?,'old session')",agentSession,project,user);
        jdbc.update("INSERT INTO agent_memory(id,project_id,type,title,content,source_type,created_by,updated_by) VALUES (?,?,'DECISION','old memory','synthetic content','MANUAL',?,?)",agentMemory,project,user,user);
        UUID auditId=UUID.randomUUID(), notificationId=UUID.randomUUID();
        jdbc.update("INSERT INTO audit_log(id,project_id,user_id,action,entity_type,entity_id,detail,request_id) VALUES (?,?,?,'DOCUMENT_UPLOADED','PROJECT_DOCUMENT',?,?::jsonb,?)",
                auditId,project,user,doc,"{\"originalFilename\":\"synthetic confidential title\"}",UUID.randomUUID());
        jdbc.update("INSERT INTO notification(id,project_id,user_id,type,title,content,entity_type,entity_id) VALUES (?,?,?,'DOCUMENT_PROCESSED','文档处理完成','synthetic confidential title','PROJECT_DOCUMENT',?)",
                notificationId,project,user,doc);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc.update("INSERT INTO connector_event_credential(connection_id,encrypt_key_cipher,verification_token_cipher,verified_at) VALUES (?,'cipher-a','cipher-b',now())",connection);
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var grants = context.getBean(ConnectorOAuthRepository.class);
            var visibility = context.getBean(ConnectorVisibilityService.class);
            var readable = context.getBean(DocumentVisibilityRepository.class);
            assertMetadataAccess(dataSource,project,user,true);
            var agentHistory = context.getBean(AgentHistoryVisibilityService.class);
            var memories = new AgentMemoryRepository(jdbc);
            var storageGate = new StorageAuthorizationService(readable,
                    new MinioProperties("http://unused", "unused", "unused", "testbucket", "", false), "172.30.173.2");
            assertThat(readable.readable(project,doc)).isTrue();
            assertThat(agentHistory.visible(project,agentSession)).isTrue();
            assertThat(memories.find(project,agentMemory)).isPresent();
            assertThat(storageGate.allowed("172.30.173.2", "/storage/testbucket/" + key)).isTrue();
            assertThat(storageGate.allowed("127.0.0.1", "/storage/testbucket/" + key)).isFalse();
            assertThat(storageGate.allowed("172.30.173.2", "/storage/testbucket/" + key + "?signature=redacted")).isFalse();
            assertThat(jdbc.queryForObject("SELECT user_authorization_id IS NULL FROM connector_source WHERE id=?",Boolean.class,source)).isTrue();

            var guard = context.getBean(KnowledgeVisibilityGuard.class);
            var emitting = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var emission = pool.submit(() -> guard.whileVisible(project,java.util.List.of(), () -> {
                    emitting.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }));
                assertThat(emitting.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var revocation = pool.submit(() -> visibility.blockSource(project,source,false));
                Thread.sleep(200);
                assertThat(revocation.isDone()).isFalse();
                release.countDown();
                emission.get();
                revocation.get();
            }
            assertThat(readable.readable(project,doc)).isFalse();
            assertThat(agentHistory.visible(project,agentSession)).isFalse();
            assertThat(memories.find(project,agentMemory)).isEmpty();
            assertThat(storageGate.allowed("172.30.173.2", "/storage/testbucket/" + key)).isFalse();
            assertMetadataAccess(dataSource,project,user,false);
            assertThat(visibility.restoreSource(project,source)).isTrue();
            assertThat(visibility.restoreDocument(project,source,external)).isTrue();
            assertThat(readable.readable(project,doc)).isTrue();
            assertThat(agentHistory.visible(project,agentSession)).isTrue();
            assertThat(memories.find(project,agentMemory)).isPresent();
            assertMetadataAccess(dataSource,project,user,true);

            ModelSecretCipher cipher = new ModelSecretCipher("synthetic-test-master-key-only");
            String encryptedAccess = cipher.encrypt("synthetic-access"), encryptedRefresh = cipher.encrypt("synthetic-refresh");
            assertThat(encryptedAccess).doesNotContain("synthetic-access");
            assertThat(cipher.decrypt(encryptedRefresh)).isEqualTo("synthetic-refresh");
            UUID authorization = grants.saveAuthorization(project,connection,user,encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"offline_access drive:drive:readonly docx:document:readonly",null,"b".repeat(64),Instant.now().minusSeconds(1)).orElseThrow();
            jdbc.update("UPDATE connector_source SET user_authorization_id=? WHERE id=?",authorization,source);
            ConnectorRows.ConnectionRow connectionRow = new ConnectorRows.ConnectionRow();
            connectionRow.id = connection;
            when(context.getBean(ConnectorConnectionMapper.class).find(connection)).thenReturn(connectionRow);
            context.getBean(ConnectorConnectionService.class).configureEvents(user,connection,"synthetic-new-key-123","synthetic-new-token-123");
            assertThat(grants.find(project,authorization).orElseThrow().status()).isEqualTo("REAUTH_REQUIRED");
            assertThat(readable.readable(project,doc)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_deletion_journal",Integer.class)).isZero();
            var eventCredentials = context.getBean(ConnectorEventCredentialRepository.class);
            var changedCredentials = eventCredentials.find(connection).orElseThrow();
            assertThat(changedCredentials.verified()).isFalse();
            assertThat(changedCredentials.encryptKeyCipher()).doesNotContain("synthetic-new-key-123");
            assertThat(cipher.decrypt(changedCredentials.encryptKeyCipher())).isEqualTo("synthetic-new-key-123");
            assertThat(changedCredentials.toString()).doesNotContain("synthetic-new-key-123", "synthetic-new-token-123");
            assertThat(eventCredentials.markVerified(connection,changedCredentials)).isTrue();
            var afterCredentialChange = grants.find(project,authorization).orElseThrow();
            assertThat(grants.saveAuthorization(project,connection,user,encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),afterCredentialChange.scope(),
                    afterCredentialChange.version(),"b".repeat(64),Instant.now().minusSeconds(1))).contains(authorization);
            assertThat(visibility.restoreSource(project,source)).isTrue();
            assertThat(visibility.restoreDocument(project,source,external)).isTrue();
            UUID disposableProject=UUID.randomUUID(), disposableSource=UUID.randomUUID();
            jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'disposable',?,?)",disposableProject,user,user);
            UUID disposableGrant=grants.saveAuthorization(disposableProject,connection,user,encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),afterCredentialChange.scope(),null,
                    "e".repeat(64),Instant.now()).orElseThrow();
            jdbc.update("INSERT INTO connector_source(id,project_id,connection_id,folder_token,name,authorization_declared_by,authorization_declared_at,user_authorization_id) VALUES (?,?,?,'disposable','source',?,now(),?)",disposableSource,disposableProject,connection,user,disposableGrant);
            grants.saveFlow("f".repeat(64),disposableProject,connection,user,cipher.encrypt("synthetic-verifier"),
                    "https://example.invalid/api/v1/connector/oauth/callback",Instant.now().plusSeconds(300));
            jdbc.update("DELETE FROM project WHERE id=?",disposableProject);
            assertThat(grants.find(disposableProject,disposableGrant)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_oauth_flow WHERE project_id=?",Integer.class,disposableProject)).isZero();
            UUID otherAuthorization = grants.saveAuthorization(otherProject,connection,user,encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"offline_access drive:drive:readonly docx:document:readonly",null,"c".repeat(64),Instant.now().minusSeconds(1)).orElseThrow();
            var events = context.getBean(ConnectorEventProcessor.class);
            Instant revokedAt = Instant.now();
            SqlSessionFactoryBean processingFactory = new SqlSessionFactoryBean();
            processingFactory.setDataSource(dataSource);
            SqlSessionFactory processingSql = processingFactory.getObject();
            processingSql.getConfiguration().getTypeHandlerRegistry().register(UUID.class,
                    com.shitulelv.aicollab.common.persistence.PostgresUuidTypeHandler.class);
            processingSql.getConfiguration().addMapper(DocumentMapper.class);
            jdbc.update("UPDATE project_document SET status='UPLOADED' WHERE id=?",doc);
            UUID processingToken = UUID.randomUUID();
            try (var sqlSession = processingSql.openSession(true)) {
                var mapper = sqlSession.getMapper(DocumentMapper.class);
                assertThat(mapper.claim(project,doc,processingToken)).isEqualTo(1);
                events.revokeTokens(connection,"b".repeat(64),"synthetic-event-1",revokedAt);
                assertThat(mapper.heartbeat(project,doc,processingToken,DocumentStatus.PARSING)).isZero();
                assertThat(mapper.markIndexing(project,doc,processingToken,"synthetic")).isZero();
            }
            jdbc.update("UPDATE project_document SET status='READY',processing_token=NULL WHERE id=?",doc);
            UUID lateProject = UUID.randomUUID();
            jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'late',?,?)",lateProject,user,user);
            events.revokeTokens(connection,"d".repeat(64),"synthetic-event-before-callback",revokedAt);
            assertThat(grants.saveAuthorization(lateProject,connection,user,encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"offline_access",null,
                    "d".repeat(64),revokedAt.minusSeconds(1))).isEmpty();
            assertThat(grants.find(project,authorization).orElseThrow().status()).isEqualTo("REAUTH_REQUIRED");
            assertThat(grants.find(otherProject,otherAuthorization).orElseThrow().status()).isEqualTo("ACTIVE");
            assertThat(readable.readable(project,doc)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_deletion_journal",Integer.class)).isZero();
            var afterEvent = grants.find(project,authorization).orElseThrow();
            assertThat(grants.saveAuthorization(project,connection,user,encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),afterEvent.scope(),afterEvent.version(),
                    "b".repeat(64),revokedAt.plusSeconds(1))).contains(authorization);
            events.revokeTokens(connection,"b".repeat(64),"synthetic-event-1",revokedAt);
            events.revokeTokens(connection,"b".repeat(64),"synthetic-delayed-event",revokedAt);
            assertThat(grants.find(project,authorization).orElseThrow().status()).isEqualTo("ACTIVE");
            assertThat(visibility.restoreSource(project,source)).isTrue();
            assertThat(visibility.restoreDocument(project,source,external)).isTrue();
            long version = grants.find(project,authorization).orElseThrow().version();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> grants.claimRefresh(project,authorization,version,UUID.randomUUID()));
                var second = pool.submit(() -> grants.claimRefresh(project,authorization,version,UUID.randomUUID()));
                var claims = java.util.stream.Stream.of(first.get(),second.get()).flatMap(java.util.Optional::stream).toList();
                assertThat(claims).hasSize(1);
                var claim = claims.getFirst();
                assertThat(grants.completeRefresh(project,authorization,claim.version(),claim.claim(),
                        cipher.encrypt("rotated-access"),cipher.encrypt("rotated-refresh"),Instant.now().plusSeconds(3600),
                        Instant.now().plusSeconds(7200),claim.scope(),"ACTIVE")).isTrue();
            }
            var active = grants.find(project,authorization).orElseThrow();
            assertThat(cipher.decrypt(active.refreshCipher())).isEqualTo("rotated-refresh");
            grants.saveFlow("a".repeat(64),project,connection,user,cipher.encrypt("synthetic-verifier"),
                    "https://example.invalid/api/v1/connector/oauth/callback",Instant.now().plusSeconds(300));
            var stale = grants.claimRefresh(project,authorization,active.version(),UUID.randomUUID()).orElseThrow();
            assertThat(grants.revoke(project,authorization)).isTrue();
            var oldFlow = grants.consumeFlow("a".repeat(64)).orElseThrow();
            assertThat(grants.saveAuthorization(project,connection,user,encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),active.scope(),oldFlow.grantVersion(),"b".repeat(64),Instant.now())).isEmpty();
            assertThat(grants.completeRefresh(project,authorization,stale.version(),stale.claim(),encryptedAccess,encryptedRefresh,
                    Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),active.scope(),"ACTIVE")).isFalse();
            assertThat(readable.readable(project,doc)).isFalse();
            assertThat(readable.readable(otherProject,otherDoc)).isTrue();
            assertMetadataAccess(dataSource,project,user,false);

            visibility.blockSource(project,source,true);
            assertThat(agentHistory.visible(project,agentSession)).isFalse();
            assertThat(memories.find(project,agentMemory)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT access_blocked FROM project_document WHERE id=?",Boolean.class,doc)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_deletion_journal WHERE project_id=?",Integer.class,project)).isEqualTo(1);
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            SqlSessionFactory factory = factoryBean.getObject();
            factory.getConfiguration().setMapUnderscoreToCamelCase(true);
            factory.getConfiguration().getTypeHandlerRegistry().register(UUID.class,
                    com.shitulelv.aicollab.common.persistence.PostgresUuidTypeHandler.class);
            factory.getConfiguration().addMapper(KnowledgeMessageMapper.class);
            try (var sqlSession = factory.openSession()) {
                assertThat(sqlSession.getMapper(KnowledgeMessageMapper.class).listOwn(project,session,user).getFirst().getContent())
                        .doesNotContain("previous synthetic answer");
            }

            DocumentStorageGateway storage = mock(DocumentStorageGateway.class);
            doThrow(new IllegalStateException("synthetic outage")).doNothing().when(storage).delete(key);
            ConnectorCleanupService worker = new ConnectorCleanupService(context.getBean(ConnectorCleanupRepository.class),storage,true);
            worker.processDue();
            assertThat(jdbc.queryForObject("SELECT status FROM connector_cleanup_job",String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM project_document WHERE id=?",Integer.class,doc)).isEqualTo(1);
            jdbc.update("UPDATE connector_cleanup_job SET next_attempt_at=now()");
            worker.processDue();
            assertThat(jdbc.queryForObject("SELECT status FROM connector_cleanup_job",String.class)).isEqualTo("COMPLETE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM project_document WHERE id=?",Integer.class,doc)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE document_id=?",Integer.class,doc)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_body WHERE document_id=?",Integer.class,doc)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_body_chunk WHERE document_id=?",Integer.class,doc)).isZero();
            var removedMetadata = jdbc.queryForMap("SELECT title,source_url,content_hash,latest_modify_time,external_key FROM external_document WHERE id=?",external);
            assertThat(removedMetadata.get("title")).isNull();
            assertThat(removedMetadata.get("source_url")).isNull();
            assertThat(removedMetadata.get("content_hash")).isNull();
            assertThat(removedMetadata.get("latest_modify_time")).isNull();
            assertThat(removedMetadata.get("external_key")).isEqualTo("revoked:"+external);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM external_document_revision WHERE external_document_id=? AND (content_hash IS NOT NULL OR latest_modify_time IS NOT NULL)",Integer.class,external)).isZero();
            assertThat(jdbc.queryForObject("SELECT detail::text FROM audit_log WHERE id=?",String.class,auditId)).isEqualTo("{}");
            assertThat(jdbc.queryForObject("SELECT content FROM notification WHERE id=?",String.class,notificationId)).isEqualTo("关联文档已不可访问");
            assertMetadataAccess(dataSource,project,user,false);
            var notificationRepository=mock(com.shitulelv.aicollab.notification.infrastructure.repository.NotificationRepository.class);
            var notificationService = new com.shitulelv.aicollab.notification.application.service.NotificationApplicationService(
                    notificationRepository,mock(com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard.class),readable);
            notificationService.create(project,user,"DOCUMENT_PROCESSED","synthetic late title","synthetic late text","PROJECT_DOCUMENT",doc);
            var captured=org.mockito.ArgumentCaptor.forClass(com.shitulelv.aicollab.notification.infrastructure.entity.NotificationEntity.class);
            verify(notificationRepository).create(captured.capture());
            assertThat(captured.getValue().getTitle()).isEqualTo("文档通知");
            assertThat(captured.getValue().getContent()).isEqualTo("关联文档已不可访问");
            assertThat(readable.readable(otherProject,otherDoc)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_deletion_journal",Integer.class)).isEqualTo(1);

            // Simulate an old backup reintroducing a previously deleted row, then replay its independent tombstone.
            jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) VALUES (?,?,'restored','doc.txt','text/plain',1,?,'READY',?)",doc,project,key,user);
            jdbc.update("UPDATE external_document SET status='ACTIVE' WHERE id=?",external);
            var replay = context.getBean(ConnectorDeletionReplayService.class);
            var mismatch = new ConnectorDeletionReplayService.Tombstone(project,source,doc,key + ".wrong");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> replay.replay(java.util.List.of(mismatch)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT access_blocked FROM project_document WHERE id=?",Boolean.class,doc)).isFalse();
            replay.replay(java.util.List.of(new ConnectorDeletionReplayService.Tombstone(project,source,doc,key)));
            assertThat(jdbc.queryForObject("SELECT access_blocked FROM project_document WHERE id=?",Boolean.class,doc)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM connector_cleanup_job",String.class)).isEqualTo("PENDING");
            worker.processDue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM project_document WHERE id=?",Integer.class,doc)).isZero();
            assertThat(readable.readable(otherProject,otherDoc)).isTrue();
        }
    }

    private static void assertMetadataAccess(DataSource ds, UUID project, UUID user, boolean visible) throws Exception {
        var bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        var factory = bean.getObject();
        factory.getConfiguration().setMapUnderscoreToCamelCase(true);
        factory.getConfiguration().getTypeHandlerRegistry().register(UUID.class,
                com.shitulelv.aicollab.common.persistence.PostgresUuidTypeHandler.class);
        factory.getConfiguration().addMapper(AuditLogMapper.class);
        factory.getConfiguration().addMapper(NotificationMapper.class);
        factory.getConfiguration().addMapper(ProjectDashboardMapper.class);
        try (var sqlSession=factory.openSession(true)) {
            var audit=sqlSession.getMapper(AuditLogMapper.class).page(project,10,0).getFirst();
            var notification=sqlSession.getMapper(NotificationMapper.class).listByProject(user,project,10,0).getFirst();
            var documents=sqlSession.getMapper(ProjectDashboardMapper.class).listRecentDocuments(project,10);
            if (visible) {
                assertThat(audit.detailJson()).contains("synthetic confidential title");
                assertThat(notification.getContent()).isEqualTo("synthetic confidential title");
                assertThat(documents).hasSize(1);
            } else {
                assertThat(audit.detailJson()).isEqualTo("{}");
                assertThat(notification.getContent()).isEqualTo("关联文档已不可访问");
                assertThat(documents).isEmpty();
            }
        }
    }
}
