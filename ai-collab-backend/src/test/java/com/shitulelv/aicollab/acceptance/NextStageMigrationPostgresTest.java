package com.shitulelv.aicollab.acceptance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class NextStageMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @Test void upgradesV53WithExistingConversationAndDocumentWithoutChangingTheirData() {
        var ds=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("53").load().migrate();
        UUID user=UUID.randomUUID(),project=UUID.randomUUID(),session=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,'upgrade-user','test-hash','升级用户')",user);
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'升级项目',?,?)",project,user,user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",project,user);
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title,working_state) VALUES (?,?,?,'旧会话','{\"summary\":{\"text\":\"保留旧摘要\",\"segments\":[]}}'::jsonb)",session,project,user);
        jdbc.update("INSERT INTO agent_message(session_id,role,content) VALUES (?,'USER','旧消息不可改写')",session);
        jdbc.update("INSERT INTO project_document(project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) VALUES (?,'旧正文','old.txt','text/plain',10,'fixture/old','FAILED',?)",project,user);
        var digests=new LinkedHashMap<String,String>();var columns=new LinkedHashMap<String,String>();
        for(String table:jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename",String.class)) {
            String names=String.join(",",jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position",String.class,table).stream().map(NextStageMigrationPostgresTest::quote).toList());
            columns.put(table,names);digests.put(table,digest(jdbc,table,names));
        }
        var result=Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(2);
        columns.forEach((table,names)->assertThat(digest(jdbc,table,names)).as(table+" existing columns").isEqualTo(digests.get(table)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_body",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_planning_operation",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT working_state->'summary'->>'text' FROM agent_session WHERE id=?",String.class,session)).isEqualTo("保留旧摘要");
    }
    private static String quote(String id){return "\""+id.replace("\"","\"\"")+"\"";}
    private static String digest(JdbcTemplate jdbc,String table,String columns){return jdbc.queryForObject("SELECT md5(coalesce(string_agg(value,E'\\n' ORDER BY value),'')) FROM (SELECT row_to_json(t)::text value FROM (SELECT "+columns+" FROM "+quote(table)+") t) v",String.class);}
}
