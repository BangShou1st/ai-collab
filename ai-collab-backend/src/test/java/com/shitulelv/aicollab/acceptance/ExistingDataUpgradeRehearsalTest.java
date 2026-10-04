package com.shitulelv.aicollab.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class ExistingDataUpgradeRehearsalTest {
    record Snapshot(String columns, long rows, String digest) {}
    @Test @EnabledIfEnvironmentVariable(named="AI_UPGRADE_REHEARSAL",matches="true")
    void upgradesOfflineBusinessCopyWithoutChangingExistingColumnsOrRows() throws Exception {
        var dataSource=AcceptanceDatabaseSupport.dataSource();
        var jdbc=new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success",Integer.class)).isEqualTo(45);
        var snapshots=new LinkedHashMap<String,Snapshot>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname='public' and tablename<>'flyway_schema_history' order by tablename",String.class)) {
            String columns=jdbc.queryForList("select column_name from information_schema.columns where table_schema='public' and table_name=? order by ordinal_position",String.class,table)
                    .stream().map(ExistingDataUpgradeRehearsalTest::quote).collect(Collectors.joining(","));
            snapshots.put(table,new Snapshot(columns,jdbc.queryForObject("select count(*) from "+quote(table),Long.class),digest(jdbc,table,columns)));
        }
        var result=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(11);
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success",Integer.class)).isEqualTo(56);
        for (var item:snapshots.entrySet()) {
            assertThat(jdbc.queryForObject("select count(*) from "+quote(item.getKey()),Long.class)).as(item.getKey()+" row count").isEqualTo(item.getValue().rows());
            assertThat(digest(jdbc,item.getKey(),item.getValue().columns())).as(item.getKey()+" original columns digest").isEqualTo(item.getValue().digest());
        }
        var repository=new TaskPlanRepository(jdbc,new ObjectMapper());
        for (var row:jdbc.queryForList("select project_id,id,latest_version_id from ai_task_plan")) {
            UUID project=(UUID)row.get("project_id"), plan=(UUID)row.get("id");
            assertThat(repository.require(project,plan)).isNotNull();
            if(row.get("latest_version_id")!=null) assertThat(repository.requireVersion(project,plan,(UUID)row.get("latest_version_id"))).isNotNull();
        }
        for(String table:List.of("user_ai_provider","system_embedding_config","ai_task_plan","ai_task_plan_version","agent_approval","knowledge_citation"))
            System.out.println("UPGRADE_REHEARSAL table="+table+", preservedRows="+snapshots.get(table).rows());
        System.out.println("UPGRADE_REHEARSAL V45 -> V55, all existing table digests unchanged; original source volume remains stopped");
    }
    private static String quote(String identifier) { return "\""+identifier.replace("\"","\"\"")+"\""; }
    private static String digest(JdbcTemplate jdbc,String table,String columns) {
        return jdbc.queryForObject("select md5(coalesce(string_agg(row_value,E'\\n' order by row_value),'')) from (select row_to_json(t)::text row_value from (select "+columns+" from "+quote(table)+") t) values_to_hash",String.class);
    }
}
