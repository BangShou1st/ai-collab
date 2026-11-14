package com.shitulelv.aicollab.agent.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentMemorySourceVerifier;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemorySourceRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.ListMemoriesAgentTool;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.persistence.PostgresUuidTypeHandler;
import com.shitulelv.aicollab.project.domain.policy.DatabaseProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.infrastructure.mapper.ProjectMemberMapper;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * AC07-P1 验收门禁补齐(AC07-P0 报告 §8.5 与 §4.2 边界三):
 * <ul>
 *   <li>M-07 成员移除:真实 {@link DatabaseProjectAccessGuard} + 真实 project_member 表,移除后召回/列表/工具全部拒绝;</li>
 *   <li>M-05 撤权:直接钉住 {@code AgentMemoryRepository.list()} 的三段过滤(来源屏蔽、授权过期、外部文档 BLOCKED),
 *       不经过 {@code blockSource} 的隐藏表,确保 SQL 本身 fail-closed,并钉住"撤权事件之后创建的记忆仍可见"的边界;</li>
 *   <li>M-04 禁用:disable 后 context/管理列表过滤/工具都不再召回,管理视图仍可复核;</li>
 *   <li>M-13 并发 CAS:同版本并发更新恰好一个成功,其余明确 VERSION_CONFLICT,修订历史只写一条。</li>
 * </ul>
 * 全部走真实 PostgreSQL + Flyway 全量迁移 + 真实 Repository/Service,零模型调用。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentMemoryRecallGovernanceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    static DataSource dataSource;
    static JdbcTemplate jdbc;
    /** 与生产一致:SqlSessionTemplate 每次操作后清理会话缓存,不会把成员角色缓存到跨请求。 */
    static SqlSessionTemplate mybatis;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @BeforeAll static void setup() {
        dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(UUID.class, PostgresUuidTypeHandler.class);
        configuration.addMapper(ProjectMemberMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        mybatis = new SqlSessionTemplate(factory);
    }

    // ---------------------------------------------------------------- M-07

    @Test
    void removedMemberLosesRecallListAndToolAccessWhileOwnerKeepsIt() {
        UUID owner = user("member-removal-owner"), member = user("member-removal-member");
        UUID project = project(owner, "member-removal");
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER'),(?,?,'MEMBER')",
                project, owner, project, member);
        AgentMemoryService service = realGuardService();
        ListMemoriesAgentTool tool = tool(service);

        AgentMemoryView memory = service.create(project, owner, request("DECISION", "数据库选型 PostgreSQL", "MANUAL", null));

        // 移除前:成员可召回、可列表、可经工具读取
        assertThat(ids(service.list(project, member, true))).contains(memory.id());
        assertThat(ids(service.context(project, member, "数据库选型 PostgreSQL"))).contains(memory.id());
        assertThat(toolIds(tool, project, member)).containsExactly(memory.id());

        jdbc.update("DELETE FROM project_member WHERE project_id=? AND user_id=?", project, member);

        // 移除后:任何入口都重新校验成员身份,不依赖旧会话或缓存
        assertThatThrownBy(() -> service.list(project, member, true))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
        assertThatThrownBy(() -> service.context(project, member, "数据库选型 PostgreSQL"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
        assertThatThrownBy(() -> toolIds(tool, project, member))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));

        // 管理员不受影响
        assertThat(ids(service.list(project, owner, true))).contains(memory.id());
    }

    // ---------------------------------------------------------------- M-05

    @Test
    void repositoryListPathIsFailClosedForBlockedSourceExpiredAuthorizationAndBlockedDocument() {
        UUID admin = user("revocation-admin");
        AgentMemoryService service = mockGuardService();
        ListMemoriesAgentTool tool = tool(service);
        AgentMemoryRepository repository = new AgentMemoryRepository(jdbc);

        // ---- 场景 A:来源屏蔽可直接由列表 SQL 拦住(不依赖 blockSource 写入的隐藏表)----
        UUID projectA = project(admin, "revocation-source");
        Connector connectorA = connector(projectA, admin);
        AgentMemoryView beforeRevocation = service.create(projectA, admin,
                request("DECISION", "来源撤权前获批的决策", "MANUAL", null));
        assertThat(ids(service.list(projectA, admin, true))).contains(beforeRevocation.id());

        jdbc.update("UPDATE connector_source SET status='BLOCKED', updated_at=? WHERE id=?",
                beforeRevocation.createdAt(), connectorA.source());
        assertThat(service.list(projectA, admin, true)).isEmpty();
        assertThat(service.context(projectA, admin, "来源撤权前获批的决策")).isEmpty();
        assertThat(toolIds(tool, projectA, admin)).isEmpty();
        assertThat(repository.find(projectA, beforeRevocation.id())).isEmpty();

        // 边界:撤权事件之后创建的记忆不在隐藏时间窗内,保持可见(时间条件方向)
        AgentMemoryView afterRevocation = service.create(projectA, admin,
                request("LESSON", "撤权之后的独立经验", "MANUAL", null));
        assertThat(ids(service.list(projectA, admin, true))).containsExactly(afterRevocation.id());

        jdbc.update("UPDATE connector_source SET status='ACTIVE', updated_at=now() WHERE id=?", connectorA.source());
        assertThat(ids(service.list(projectA, admin, true)))
                .contains(beforeRevocation.id(), afterRevocation.id());

        // ---- 场景 B:外部文档 BLOCKED 同样使项目内早于该事件的记忆不可召回 ----
        UUID projectB = project(admin, "revocation-document");
        Connector connectorB = connector(projectB, admin);
        AgentMemoryView documentBound = service.create(projectB, admin,
                request("CONSTRAINT", "依据外部文档的约束", "MANUAL", null));
        UUID external = UUID.randomUUID();
        jdbc.update("INSERT INTO external_document(id,source_id,external_key,doc_type,status,updated_at) " +
                        "VALUES (?,?,'synthetic-key','docx','BLOCKED',?)",
                external, connectorB.source(), documentBound.createdAt());
        assertThat(service.list(projectB, admin, true)).isEmpty();
        assertThat(toolIds(tool, projectB, admin)).isEmpty();

        jdbc.update("UPDATE external_document SET status='ACTIVE' WHERE id=?", external);
        assertThat(ids(service.list(projectB, admin, true))).containsExactly(documentBound.id());

        // ---- 场景 C:授权 access_expires_at 过期时,早于过期点创建的记忆不可召回 ----
        UUID projectC = project(admin, "revocation-authorization");
        Connector connectorC = connector(projectC, admin);
        AgentMemoryView expiredScope = service.create(projectC, admin,
                request("DECISION", "授权有效期内确认的决策", "MANUAL", null));
        jdbc.update("UPDATE agent_memory SET created_at=now()-interval '2 days' WHERE id=?", expiredScope.id());
        UUID authorization = UUID.randomUUID();
        jdbc.update("INSERT INTO connector_oauth_authorization(id,project_id,connection_id,user_id,status," +
                        "access_token_cipher,refresh_token_cipher,access_expires_at,refresh_expires_at,feishu_subject_hash) " +
                        "VALUES (?,?,?,?,'ACTIVE','cipher','cipher',now()-interval '1 day',now()+interval '10 days',?)",
                authorization, projectC, connectorC.connection(), admin, "a".repeat(64));
        jdbc.update("UPDATE connector_source SET user_authorization_id=?, updated_at=now() WHERE id=?",
                authorization, connectorC.source());

        assertThat(service.list(projectC, admin, true)).isEmpty();
        assertThat(service.context(projectC, admin, "授权有效期内确认的决策")).isEmpty();

        jdbc.update("UPDATE connector_oauth_authorization SET access_expires_at=now()+interval '1 hour', " +
                "updated_at=now() WHERE id=?", authorization);
        assertThat(ids(service.list(projectC, admin, true))).containsExactly(expiredScope.id());
    }

    // ---------------------------------------------------------------- M-04

    @Test
    void disabledMemoryIsNotRecalledButStaysVisibleInManagementView() {
        UUID admin = user("disable-admin");
        UUID project = project(admin, "disable");
        AgentMemoryService service = mockGuardService();
        ListMemoriesAgentTool tool = tool(service);

        AgentMemoryView memory = service.create(project, admin,
                request("DECISION", "统一使用 PostgreSQL", "MANUAL", null));
        assertThat(ids(service.context(project, admin, "PostgreSQL 选型"))).contains(memory.id());

        service.disable(project, memory.id(), admin, 0);

        // 新会话/新服务实例同样不可召回(不依赖进程内状态)
        AgentMemoryService fresh = mockGuardService();
        assertThat(fresh.context(project, admin, "PostgreSQL 选型")).isEmpty();
        assertThat(fresh.list(project, admin, true)).isEmpty();
        assertThat(toolIds(tool(fresh), project, admin)).isEmpty();
        // 管理视图仍可见,便于管理员复核
        assertThat(ids(fresh.list(project, admin, false))).containsExactly(memory.id());
    }

    // ---------------------------------------------------------------- M-13

    @Test
    void concurrentUpdatesWithTheSameVersionProduceExactlyOneWinner() throws Exception {
        UUID admin = user("cas-admin");
        UUID project = project(admin, "cas");
        AgentMemoryService service = mockGuardService();
        AgentMemoryView memory = service.create(project, admin, request("DECISION", "并发基线", "MANUAL", null));

        int threads = 6;
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var barrier = new CyclicBarrier(threads);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        transactions.executeWithoutResult(status -> service.update(project, memory.id(), admin,
                                new AgentMemoryRequest("DECISION", "并发改写-" + index, "并发内容-" + index,
                                        "MANUAL", null, 0, null, null)));
                        return "OK";
                    } catch (BusinessException exception) {
                        return exception.getErrorCode().name();
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
            assertThat(outcomes).filteredOn(ErrorCode.VERSION_CONFLICT.name()::equals).hasSize(threads - 1);
        }

        assertThat(jdbc.queryForObject("SELECT version FROM agent_memory WHERE id=?", Integer.class, memory.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory_revision WHERE memory_id=?",
                Integer.class, memory.id())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private static AgentMemoryService realGuardService() {
        var guard = new DatabaseProjectAccessGuard(
                new ProjectMemberRepository(mybatis.getMapper(ProjectMemberMapper.class)));
        return new AgentMemoryService(new AgentMemoryRepository(jdbc), guard, verifier());
    }

    private static AgentMemoryService mockGuardService() {
        return new AgentMemoryService(new AgentMemoryRepository(jdbc), mock(ProjectAccessGuard.class), verifier());
    }

    private static AgentMemorySourceVerifier verifier() {
        return new AgentMemorySourceVerifier(new AgentMemorySourceRepository(jdbc));
    }

    private static ListMemoriesAgentTool tool(AgentMemoryService service) {
        return new ListMemoriesAgentTool(service, JSON);
    }

    private static AgentToolContext context(UUID project, UUID user) {
        return new AgentToolContext(UUID.randomUUID(), project, user, "MEMBER", false, 0);
    }

    private static List<UUID> toolIds(ListMemoriesAgentTool tool, UUID project, UUID user) {
        AgentToolResult result = tool.execute(context(project, user), JSON.createObjectNode());
        assertThat(result.status()).isEqualTo(AgentToolResult.Status.SUCCEEDED);
        List<UUID> ids = new ArrayList<>();
        result.data().forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }

    private static List<UUID> ids(List<AgentMemoryView> memories) {
        return memories.stream().map(AgentMemoryView::id).toList();
    }

    private static AgentMemoryRequest request(String type, String title, String sourceType, UUID sourceId) {
        return new AgentMemoryRequest(type, title, title + " 内容", sourceType, sourceId, 0, null, null);
    }

    private static UUID user(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,'unused','test')",
                id, prefix + "-" + id.toString().substring(0, 8));
        return id;
    }

    private static UUID project(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                id, name + "-" + id, owner, owner);
        return id;
    }

    private record Connector(UUID connection, UUID source) {
    }

    private static Connector connector(UUID project, UUID declaredBy) {
        UUID connection = UUID.randomUUID(), source = UUID.randomUUID();
        jdbc.update("INSERT INTO connector_connection(id,name,app_id,app_secret_cipher,created_by) " +
                        "VALUES (?,?,?,'cipher',?)",
                connection, "conn-" + connection, "app-" + connection, declaredBy);
        jdbc.update("INSERT INTO connector_source(id,project_id,connection_id,folder_token,name," +
                        "authorization_declared_by,authorization_declared_at) " +
                        "VALUES (?,?,?,?, 'synthetic', ?, now())",
                source, project, connection, "folder-" + source, declaredBy);
        return new Connector(connection, source);
    }
}
