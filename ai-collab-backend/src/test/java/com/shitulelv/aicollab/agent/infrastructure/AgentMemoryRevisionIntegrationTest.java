package com.shitulelv.aicollab.agent.infrastructure;

import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentMemorySourceVerifier;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryRevisionView;
import com.shitulelv.aicollab.agent.application.view.AgentMemoryView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemoryRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentMemorySourceRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * AC07-P1 第二波集成验证:V88 修订历史表、REVISE/DISABLE/SUPERSEDE 修订快照、
 * SUPERSEDE 召回过滤与冲突防护(自指/环/同项目/CAS 并发),以及第一波验证状态回归。
 * 全部走真实 PostgreSQL + Flyway V1-V88 + 真实 Repository/Service,权限守卫用 mock,零模型调用。
 * <p>生产环境 Service 以 Spring 代理生效 @Transactional(修订写入与主表更新同事务);
 * 测试内直接 new 的 Service 无代理,统一用 TransactionTemplate 包出等价事务边界,
 * 同时使 SUPERSEDE 的行级锁(FOR UPDATE)真正持有到事务提交。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentMemoryRevisionIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void revisionLifecycleSupersedeAndConflictGuards() throws Exception {
        DataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        // 先迁移到 V87 建立 legacy 行,再迁到 V88,验证存量行 superseded_by 默认 NULL
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("87").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), otherProject = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,'memory-admin','unused','test')", user);
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'one',?,?),(?,'two',?,?)",
                project, user, user, otherProject, user, user);
        UUID legacyMemory = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_memory(id,project_id,type,title,content,source_type,created_by,updated_by) " +
                        "VALUES (?,?,'DECISION','legacy decision','legacy content','MANUAL',?,?)",
                legacyMemory, project, user, user);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        // ---- V88 迁移:存量行 superseded_by 为 NULL,修订表 operation CHECK 兜底 ----
        assertThat(jdbc.queryForObject("SELECT superseded_by IS NULL FROM agent_memory WHERE id=?",
                Boolean.class, legacyMemory)).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_memory_revision(project_id,memory_id,operation," +
                        "before_content,after_content,before_state,after_state,verification_state_before," +
                        "verification_state_after,actor_id,prev_version) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                project, legacyMemory, "CREATE", "a", "b", "ACTIVE", "ACTIVE", "UNVERIFIED", "UNVERIFIED", user, 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        // ---- 真实 Service 装配(权限守卫 mock) + 等价事务边界 ----
        var access = mock(ProjectAccessGuard.class);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var service = new AgentMemoryService(new AgentMemoryRepository(jdbc), access,
                new AgentMemorySourceVerifier(new AgentMemorySourceRepository(jdbc)));

        // ---- REVISE:update 生效写入一条修订快照,与主表同事务 ----
        AgentMemoryView created = tx.execute(s ->
                service.create(project, user, request("DECISION", "数据库选型", "MANUAL", null, null)));
        AgentMemoryView revised = tx.execute(s -> service.update(project, created.id(), user,
                new AgentMemoryRequest("DECISION", "数据库选型 v2", "统一使用 PostgreSQL 17",
                        "MANUAL", null, created.version(), "VERIFIED", "按评审结论修订")));
        assertThat(revised.version()).isEqualTo(created.version() + 1);
        List<AgentMemoryRevisionView> revisions = service.revisions(project, created.id(), user);
        assertThat(revisions).hasSize(1);
        AgentMemoryRevisionView revise = revisions.get(0);
        assertThat(revise.operation()).isEqualTo("REVISE");
        assertThat(revise.beforeContent()).isEqualTo(created.content());
        assertThat(revise.afterContent()).isEqualTo("统一使用 PostgreSQL 17");
        assertThat(revise.beforeState()).isEqualTo("ACTIVE");
        assertThat(revise.afterState()).isEqualTo("ACTIVE");
        assertThat(revise.verificationStateBefore()).isEqualTo("UNVERIFIED");
        assertThat(revise.verificationStateAfter()).isEqualTo("VERIFIED");
        assertThat(revise.actorId()).isEqualTo(user);
        assertThat(revise.prevVersion()).isEqualTo(created.version());
        assertThat(revise.reason()).isEqualTo("按评审结论修订");
        assertThat(revise.supersededByTargetId()).isNull();

        // ---- CAS 冲突:用已过期 version 重放 update 只会失败,不产生第二条修订 ----
        assertThatThrownBy(() -> tx.execute(s -> service.update(project, created.id(), user,
                new AgentMemoryRequest("DECISION", "过期重放", "stale", "MANUAL", null,
                        created.version(), null, null))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        assertThat(service.revisions(project, created.id(), user)).hasSize(1);

        // ---- SUPERSEDE:旧记忆指向替代者;召回/工具视图过滤,管理视图可见,写入 SUPERSEDE 修订 ----
        AgentMemoryView replacement = tx.execute(s ->
                service.create(project, user, request("DECISION", "数据库选型 最终版", "MANUAL", null, null)));
        AgentMemoryView superseded = tx.execute(s -> service.supersede(project, created.id(), user,
                replacement.id(), "新决策已确认,旧决策退出召回"));
        assertThat(superseded.supersededBy()).isEqualTo(replacement.id());
        assertThat(superseded.status()).isEqualTo("ACTIVE"); // 替代不禁用,管理视图仍可见
        assertThat(superseded.version()).isEqualTo(created.version() + 2);
        List<AgentMemoryRevisionView> afterSupersede = service.revisions(project, created.id(), user);
        assertThat(afterSupersede).hasSize(2);
        AgentMemoryRevisionView supersedeRevision = afterSupersede.get(0);
        assertThat(supersedeRevision.operation()).isEqualTo("SUPERSEDE");
        assertThat(supersedeRevision.supersededByTargetId()).isEqualTo(replacement.id());
        assertThat(supersedeRevision.reason()).isEqualTo("新决策已确认,旧决策退出召回");
        assertThat(supersedeRevision.prevVersion()).isEqualTo(created.version() + 1);
        assertThat(supersedeRevision.beforeContent()).isEqualTo("统一使用 PostgreSQL 17");

        // M-03:被替代旧记忆不再进入召回(activeOnly=true)与 context(),管理视图仍可见
        assertThat(service.list(project, user, true)).extracting(AgentMemoryView::id)
                .doesNotContain(created.id()).contains(replacement.id());
        assertThat(service.context(project, user, "数据库选型 PostgreSQL")).extracting(AgentMemoryView::id)
                .doesNotContain(created.id());
        assertThat(service.list(project, user, false)).extracting(AgentMemoryView::id)
                .contains(created.id(), replacement.id(), legacyMemory);

        // ---- SUPERSEDE 冲突防护:自指/环/跨项目/非 ACTIVE 目标一律拒绝 ----
        assertThatThrownBy(() -> tx.execute(s -> service.supersede(project, replacement.id(), user,
                replacement.id(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SUPERSEDE_CYCLE));
        // 环:legacy 已被指向 replacement?此处构造 B->A 后再尝试 A->B 成环拒绝
        AgentMemoryView other = tx.execute(s ->
                service.create(project, user, request("CONSTRAINT", "另一条约束", "MANUAL", null, null)));
        tx.execute(s -> service.supersede(project, replacement.id(), user, other.id(), null));
        assertThatThrownBy(() -> tx.execute(s -> service.supersede(project, other.id(), user,
                created.id(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SUPERSEDE_CYCLE));
        // 跨项目目标拒绝
        AgentMemoryView foreignMemory = tx.execute(s ->
                service.create(otherProject, user, request("DECISION", "别的项目记忆", "MANUAL", null, null)));
        assertThatThrownBy(() -> tx.execute(s -> service.supersede(project, legacyMemory, user,
                foreignMemory.id(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SUPERSEDE_TARGET_INVALID));
        // DISABLED 目标拒绝
        tx.execute(s -> service.disable(otherProject, foreignMemory.id(), user, 0));
        assertThatThrownBy(() -> tx.execute(s -> service.supersede(project, legacyMemory, user,
                foreignMemory.id(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SUPERSEDE_TARGET_INVALID));
        // 目标为"已被替代但仍 ACTIVE"的记忆允许(构成替代链 A→B→C,后续波次负责链解析),
        // 本波只把关入口:同项目、ACTIVE、无环
        AgentMemoryView chained = tx.execute(s -> service.supersede(project, legacyMemory, user,
                created.id(), null));
        assertThat(chained.supersededBy()).isEqualTo(created.id());
        assertThat(service.revisions(project, created.id(), user)).hasSize(2);

        // ---- DISABLE:disable 生成快照,reason 可追溯,prev_version 记录操作前版本 ----
        AgentMemoryView lesson = tx.execute(s ->
                service.create(project, user, request("LESSON", "教训记录", "MANUAL", null, null)));
        AgentMemoryView disabled = tx.execute(s -> service.disable(project, lesson.id(), user, 0, null,
                "与本项目现行约束矛盾,禁用待复核"));
        assertThat(disabled.status()).isEqualTo("DISABLED");
        List<AgentMemoryRevisionView> disableRevisions = service.revisions(project, lesson.id(), user);
        assertThat(disableRevisions).hasSize(1);
        AgentMemoryRevisionView disableRevision = disableRevisions.get(0);
        assertThat(disableRevision.operation()).isEqualTo("DISABLE");
        assertThat(disableRevision.beforeState()).isEqualTo("ACTIVE");
        assertThat(disableRevision.afterState()).isEqualTo("DISABLED");
        assertThat(disableRevision.beforeContent()).isEqualTo(lesson.content());
        assertThat(disableRevision.afterContent()).isEqualTo(lesson.content());
        assertThat(disableRevision.prevVersion()).isZero();
        assertThat(disableRevision.reason()).isEqualTo("与本项目现行约束矛盾,禁用待复核");

        // ---- 并发 CAS(M-13):两个管理员同版本并发 disable,恰好一个成功且只写一条修订 ----
        AgentMemoryView shared = tx.execute(s ->
                service.create(project, user, request("PREFERENCE", "并发目标", "MANUAL", null, null)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger successes = new AtomicInteger();
            List<Future<Boolean>> outcomes = List.of(
                    pool.submit(() -> runDisable(tx, service, project, shared.id(), user, successes)),
                    pool.submit(() -> runDisable(tx, service, project, shared.id(), user, successes)));
            for (Future<Boolean> outcome : outcomes) outcome.get(30, TimeUnit.SECONDS);
            assertThat(successes.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.revisions(project, shared.id(), user)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM agent_memory WHERE id=?", String.class, shared.id()))
                .isEqualTo("DISABLED");

        // ---- 第一波回归:验证状态生命周期与 UNVERIFIED 召回兼容未破坏 ----
        AgentMemoryView memory = tx.execute(s ->
                service.create(project, user, request("PREFERENCE", "回归偏好", "MANUAL", null, null)));
        AgentMemoryView verified = tx.execute(s -> service.update(project, memory.id(), user,
                new AgentMemoryRequest("PREFERENCE", "回归偏好", memory.content(), "MANUAL", null,
                        memory.version(), "VERIFIED", null)));
        assertThat(verified.verificationState()).isEqualTo("VERIFIED");
        assertThat(service.list(project, user, true)).extracting(AgentMemoryView::id).contains(memory.id());
        assertThat(service.revisions(project, memory.id(), user)).hasSize(1);
    }

    /** 单线程事务内尝试 disable;CAS 失败返回 false(不抛出),成功返回 true 并计数。 */
    private static boolean runDisable(TransactionTemplate tx, AgentMemoryService service,
                                      UUID projectId, UUID id, UUID user, AtomicInteger successes) {
        Boolean won = tx.execute(s -> {
            try {
                service.disable(projectId, id, user, 0, null, "并发审批");
                return true;
            } catch (BusinessException exception) {
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                return false;
            }
        });
        if (Boolean.TRUE.equals(won)) successes.incrementAndGet();
        return Boolean.TRUE.equals(won);
    }

    private static AgentMemoryRequest request(String type, String title, String sourceType, UUID sourceId,
                                              String verificationState) {
        return new AgentMemoryRequest(type, title, title + " 内容", sourceType, sourceId, 0, verificationState, null);
    }
}
