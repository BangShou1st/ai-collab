package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Clock;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Synthetic V45 fixtures supplement categories absent from the actual offline business copy. */
@Testcontainers(disabledWithoutDocker=true)
class LegacyApprovalCitationUpgradeTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Test void oldPendingApprovalCanBeResolvedAndLegacyCitationRemainsVisibleAfterUpgrade() throws Exception {
        var dataSource=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("45").load().migrate();
        var jdbc=new JdbcTemplate(dataSource); var json=new ObjectMapper().findAndRegisterModules();
        UUID user=UUID.randomUUID(),project=UUID.randomUUID(),session=UUID.randomUUID(),run=UUID.randomUUID(),
                step=UUID.randomUUID(),approval=UUID.randomUUID(),document=UUID.randomUUID(),chunk=UUID.randomUUID(),
                knowledge=UUID.randomUUID(),message=UUID.randomUUID(),config=UUID.randomUUID();
        jdbc.update("insert into app_user(id,username,password_hash,display_name) values (?,'legacy-upgrade-user','test-only','旧数据验收')",user);
        jdbc.update("insert into project(id,name,owner_id,created_by) values (?,'旧数据验收',?,?)",project,user,user);
        jdbc.update("insert into project_member(project_id,user_id,role) values (?,?,'OWNER')",project,user);
        jdbc.update("insert into agent_session(id,project_id,creator_id,title) values (?,?,?,'旧会话')",session,project,user);
        jdbc.update("insert into agent_run(id,session_id,project_id,requester_id,goal,status) values (?,?,?,?,'旧审批','SUCCEEDED')",run,session,project,user);
        jdbc.update("insert into agent_step(id,run_id,sequence_no,type) values (?,?,1,'APPROVAL_REQUESTED')",step,run);
        String nonceHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(approval.toString().getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
            insert into agent_approval(id,project_id,run_id,step_id,tool_name,arguments_json,arguments_hash,
              requester_id,nonce_hash,expires_at,session_id,proposal_family,subject_key)
            values (?,?,?,?,'create_task_after_approval','{"title":"旧提案"}'::jsonb,?, ?,?,now()+interval '1 hour',?,'TASK_CREATE',?)
            """,approval,project,run,step,"0".repeat(64),user,nonceHash,session,approval);
        jdbc.update("""
            insert into system_embedding_config(id,provider,base_url,model_name,dimensions,fingerprint)
            values (?,'OPENAI_COMPATIBLE','https://synthetic.example','legacy-vector',3,?)
            """,config,"a".repeat(64));
        jdbc.update("""
            insert into project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by,embedding_fingerprint)
            values (?,?,'旧资料','legacy.txt','text/plain',10,'synthetic/legacy','READY',?,?)
            """,document,project,user,"a".repeat(64));
        jdbc.update("""
            insert into document_chunk(id,project_id,document_id,chunk_no,content,content_hash,embedding_provider,embedding_model,embedding_dimension,embedding,embedding_fingerprint)
            values (?,?,?,0,'旧资料正文',?,'OPENAI_COMPATIBLE','legacy-vector',3,'[1,0,0]'::vector,?)
            """,chunk,project,document,"b".repeat(64),"a".repeat(64));
        jdbc.update("insert into knowledge_session(id,project_id,user_id,title) values (?,?,?,'旧问答')",knowledge,project,user);
        jdbc.update("insert into knowledge_message(id,session_id,role,content) values (?,?,'ASSISTANT','旧答案 [S1]')",message,knowledge);
        jdbc.update("insert into knowledge_citation(message_id,chunk_id,rank,similarity,quote_text) values (?,?,1,1,'旧资料正文')",message,chunk);
        // V46–V60：V60 为崩溃接管计时锚点（agent_run.last_progress_at）
        assertThat(Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate().migrationsExecuted).isEqualTo(16);
        UUID revisionRun=UUID.randomUUID();
        jdbc.update("insert into agent_run(id,session_id,project_id,requester_id,goal,status) values (?,?,?,?,'提案修订','SUCCEEDED')",revisionRun,session,project,user);
        jdbc.update("insert into agent_approval_revision(project_id,approval_id,source_run_id,revision,before_arguments_json,after_arguments_json,diff_json) values (?,?,?,2,'{}','{}','{}')",project,approval,revisionRun);
        var repository=new AgentApprovalRepository(jdbc,json);
        assertThat(repository.listByRun(project,revisionRun)).extracting(item->item.id()).containsExactly(approval);
        assertThat(repository.listByRun(UUID.randomUUID(),revisionRun)).isEmpty();
        assertThat(repository.find(project,approval).orElseThrow().revision()).isEqualTo(1);
        var tool=mock(ApprovalWriteAgentTool.class);
        when(tool.name()).thenReturn("create_task_after_approval");
        when(tool.execute(any(),any())).thenReturn(new AgentToolResult(json.createObjectNode().put("saved",true),List.of(),List.of()));
        var service=new AgentApprovalService(repository,new AgentToolRegistry(List.of(tool)),mock(ProjectAccessGuard.class),json,Clock.systemUTC(),mock(AgentEventService.class));
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThat(transactions.execute(status->service.approve(project,approval,user,approval.toString(),UUID.randomUUID(),1)).status()).isEqualTo("APPROVED");
        verify(tool,times(1)).execute(any(),any());
        assertThat(jdbc.queryForObject("select generation_id is null from document_chunk where id=?",Boolean.class,chunk)).isTrue();
        assertThat(jdbc.queryForObject("select quote_text from knowledge_citation where message_id=?",String.class,message)).isEqualTo("旧资料正文");
        assertThat(jdbc.queryForObject("select fingerprint from system_embedding_config where id=?",String.class,config)).isEqualTo("a".repeat(64));
        assertThat(jdbc.queryForObject("select content from knowledge_message where id=?",String.class,message)).isEqualTo("旧答案 [S1]");
    }
}
