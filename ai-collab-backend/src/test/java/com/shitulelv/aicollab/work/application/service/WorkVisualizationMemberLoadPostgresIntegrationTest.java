package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.testing.MinioTestImage;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import com.shitulelv.aicollab.work.application.view.MemberLoadView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 成员负载业务正确性集成测试（真实 PostgreSQL 17 + Testcontainers）。
 *
 * <p>口径：成员负载 = 基于任务分配与估算工时的当前工作量概览。</p>
 *
 * <p>覆盖场景：有成员无任务、成员未被分配、任务全部完成、历史+当前混合、
 * 无预计工时归类、项目存在未分配任务、多项目同成员、不同角色权限、跨项目隔离。
 * 全部使用固定数据集与明确期望值，禁止放宽断言。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class WorkVisualizationMemberLoadPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static final String MINIO_ACCESS_KEY = "memberload-access";
    private static final String MINIO_SECRET_KEY = "memberload-secret-123";

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(MinioTestImage.IMAGE))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("storage.minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("storage.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("storage.minio.secret-key", () -> MINIO_SECRET_KEY);
    }

    /** 与数据库容器一致的 UTC 日期基准，保证逾期/未逾期断言不受测试机时区影响。 */
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate NEXT_WEEK = TODAY.plusDays(7);

    @Autowired WorkVisualizationService visualization;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TaskPlanRecoveryJob taskPlanRecoveryJob;

    // ---------- 场景 1：有成员无任务 ----------

    @Test
    void membersWithNoTasksStillAppearWithZeroLoad() {
        UUID owner = uuid("0001");
        UUID member = uuid("0002");
        UUID project = uuid("1001");
        seedUser(owner, "ml-no-task-owner", "负载无任务所有者");
        seedUser(member, "ml-no-task-member", "负载无任务成员");
        seedProject(project, owner);
        seedMember(project, owner, "OWNER");
        seedMember(project, member, "MEMBER");

        MemberLoadView view = visualization.getMemberLoad(project, owner);

        assertThat(view.members()).hasSize(2);
        assertThat(view.unassignedUncompletedTasks()).isZero();

        MemberLoadView.MemberLoad ownerLoad = byUser(view, owner);
        assertThat(ownerLoad.displayName()).isEqualTo("负载无任务所有者");
        assertThat(ownerLoad.totalTasks()).isZero();
        assertThat(ownerLoad.completedTasks()).isZero();
        assertThat(ownerLoad.inProgressTasks()).isZero();
        assertThat(ownerLoad.uncompletedTasks()).isZero();
        assertThat(ownerLoad.overdueTasks()).isZero();
        assertThat(ownerLoad.missingEstimateTasks()).isZero();
        assertThat(ownerLoad.totalEstimateHours()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ownerLoad.uncompletedEstimateHours()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ownerLoad.completedEstimateHours()).isEqualByComparingTo(BigDecimal.ZERO);

        MemberLoadView.MemberLoad memberLoad = byUser(view, member);
        assertThat(memberLoad.totalTasks()).isZero();
        assertThat(memberLoad.uncompletedTasks()).isZero();
    }

    // ---------- 场景 2：部分成员未被分配任何任务 ----------

    @Test
    void unassignedMemberStillAppearsAlongsideLoadedMembers() {
        UUID owner = uuid("0010");
        UUID busy = uuid("0011");
        UUID idle = uuid("0012");
        UUID project = uuid("1010");
        seedUser(owner, "ml-mix-owner", "混合所有者");
        seedUser(busy, "ml-mix-busy", "混合忙碌者");
        seedUser(idle, "ml-mix-idle", "混合闲置者");
        seedProject(project, owner);
        seedMember(project, owner, "OWNER");
        seedMember(project, busy, "MEMBER");
        seedMember(project, idle, "MEMBER");
        seedTask(uuid("2010"), project, "忙碌者任务", "TODO", busy, new BigDecimal("5.00"), NEXT_WEEK, owner);

        MemberLoadView view = visualization.getMemberLoad(project, owner);

        assertThat(view.members()).hasSize(3);
        // 未被分配任务的成员不得消失，且当前任务为 0
        MemberLoadView.MemberLoad idleLoad = byUser(view, idle);
        assertThat(idleLoad.displayName()).isEqualTo("混合闲置者");
        assertThat(idleLoad.totalTasks()).isZero();
        assertThat(idleLoad.uncompletedTasks()).isZero();
        // 有任务的成员计数正确
        MemberLoadView.MemberLoad busyLoad = byUser(view, busy);
        assertThat(busyLoad.totalTasks()).isEqualTo(1);
        assertThat(busyLoad.uncompletedTasks()).isEqualTo(1);
        assertThat(busyLoad.uncompletedEstimateHours()).isEqualByComparingTo("5.00");
        // 排序：有未完成任务者在前，零任务成员在后
        assertThat(view.members().get(0).userId()).isEqualTo(busy);
    }

    // ---------- 场景 3：任务全部完成 ----------

    @Test
    void allCompletedTasksShowZeroCurrentLoad() {
        UUID owner = uuid("0020");
        UUID worker = uuid("0021");
        UUID project = uuid("1020");
        seedUser(owner, "ml-done-owner", "完成所有者");
        seedUser(worker, "ml-done-worker", "完成执行者");
        seedProject(project, owner);
        seedMember(project, owner, "OWNER");
        seedMember(project, worker, "MEMBER");
        seedTask(uuid("2020"), project, "已完成甲", "DONE", worker, new BigDecimal("5.00"), YESTERDAY, owner);
        seedTask(uuid("2021"), project, "已完成乙", "DONE", worker, new BigDecimal("7.50"), null, owner);

        MemberLoadView view = visualization.getMemberLoad(project, worker);

        MemberLoadView.MemberLoad load = byUser(view, worker);
        assertThat(load.totalTasks()).isEqualTo(2);
        assertThat(load.completedTasks()).isEqualTo(2);
        // 已逾期但已完成的历史任务不算逾期负担
        assertThat(load.overdueTasks()).isZero();
        assertThat(load.uncompletedTasks()).isZero();
        assertThat(load.inProgressTasks()).isZero();
        assertThat(load.uncompletedEstimateHours()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(load.missingEstimateTasks()).isZero();
        // 历史累计估算口径
        assertThat(load.completedEstimateHours()).isEqualByComparingTo("12.50");
        assertThat(load.totalEstimateHours()).isEqualByComparingTo("12.50");
        // 兼容字段与明确命名字段同值
        assertThat(load.completedHours()).isEqualByComparingTo(load.completedEstimateHours());
        assertThat(view.unassignedUncompletedTasks()).isZero();
    }

    // ---------- 场景 4：历史 + 当前混合（含逾期单独标注、CANCELED 仅计入总数） ----------

    @Test
    void mixedHistoryAndCurrentTasksAreSeparated() {
        UUID owner = uuid("0030");
        UUID worker = uuid("0031");
        UUID project = uuid("1030");
        seedUser(owner, "ml-hist-owner", "历史所有者");
        seedUser(worker, "ml-hist-worker", "历史执行者");
        seedProject(project, owner);
        seedMember(project, owner, "OWNER");
        seedMember(project, worker, "MEMBER");
        // 未完成：TODO（未逾期，估算 8）
        seedTask(uuid("2030"), project, "待办任务", "TODO", worker, new BigDecimal("8.00"), NEXT_WEEK, owner);
        // 未完成：IN_PROGRESS（逾期，估算 4）
        seedTask(uuid("2031"), project, "进行中逾期", "IN_PROGRESS", worker, new BigDecimal("4.00"), YESTERDAY, owner);
        // 未完成：BLOCKED（无估算、无截止）
        seedTask(uuid("2032"), project, "阻塞无估算", "BLOCKED", worker, null, null, owner);
        // 历史：DONE（曾逾期但已完成，估算 16）
        seedTask(uuid("2033"), project, "已完成历史", "DONE", worker, new BigDecimal("16.00"), YESTERDAY, owner);
        // 取消：仅计入 totalTasks，估算 2 不进任何工时口径
        seedTask(uuid("2034"), project, "已取消", "CANCELED", worker, new BigDecimal("2.00"), null, owner);

        MemberLoadView view = visualization.getMemberLoad(project, owner);

        MemberLoadView.MemberLoad load = byUser(view, worker);
        assertThat(load.totalTasks()).isEqualTo(5);
        assertThat(load.completedTasks()).isEqualTo(1);
        assertThat(load.inProgressTasks()).isEqualTo(1);
        assertThat(load.uncompletedTasks()).isEqualTo(3);
        // 逾期单独标注：只算未完成的逾期（DONE 的历史逾期不算）
        assertThat(load.overdueTasks()).isEqualTo(1);
        // 累计估算 = 8 + 4 + 16 + 2 = 30（CANCELED 也在"全部任务"里，含其估算）
        assertThat(load.totalEstimateHours()).isEqualByComparingTo("30.00");
        // 当前负担 = 8 + 4 = 12（BLOCKED 无估算不计入，不按 0 混入）
        assertThat(load.uncompletedEstimateHours()).isEqualByComparingTo("12.00");
        assertThat(load.completedEstimateHours()).isEqualByComparingTo("16.00");
        assertThat(load.missingEstimateTasks()).isEqualTo(1);
    }

    // ---------- 场景 5：无预计工时的任务单独归类 ----------

    @Test
    void tasksWithoutEstimateAreCountedSeparatelyNotAsZero() {
        UUID owner = uuid("0040");
        UUID worker = uuid("0041");
        UUID project = uuid("1040");
        seedUser(owner, "ml-est-owner", "估算所有者");
        seedUser(worker, "ml-est-worker", "估算执行者");
        seedProject(project, owner);
        seedMember(project, owner, "OWNER");
        seedMember(project, worker, "MEMBER");
        // 未完成且无估算
        seedTask(uuid("2040"), project, "未完成无估算甲", "TODO", worker, null, NEXT_WEEK, owner);
        seedTask(uuid("2041"), project, "未完成无估算乙", "BLOCKED", worker, null, null, owner);
        // 未完成且有估算
        seedTask(uuid("2042"), project, "未完成有估算", "IN_PROGRESS", worker, new BigDecimal("3.00"), NEXT_WEEK, owner);
        // 已完成但无估算：属于历史任务，不计入"未完成估算缺失"
        seedTask(uuid("2043"), project, "已完成无估算", "DONE", worker, null, YESTERDAY, owner);

        MemberLoadView view = visualization.getMemberLoad(project, owner);

        MemberLoadView.MemberLoad load = byUser(view, worker);
        assertThat(load.totalTasks()).isEqualTo(4);
        assertThat(load.uncompletedTasks()).isEqualTo(3);
        // 估算缺失只归类未完成任务：2 个
        assertThat(load.missingEstimateTasks()).isEqualTo(2);
        // 工时合计只含有估算任务：当前 3.00，累计 3.00（无估算不按 0 混入，也不影响和值）
        assertThat(load.uncompletedEstimateHours()).isEqualByComparingTo("3.00");
        assertThat(load.totalEstimateHours()).isEqualByComparingTo("3.00");
        assertThat(load.completedEstimateHours()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------- 场景 6：项目存在未分配任务 ----------

    @Test
    void unassignedTasksAreSummarizedSeparatelyWithoutPollutingMembers() {
        UUID owner = uuid("0050");
        UUID worker = uuid("0051");
        UUID project = uuid("1050");
        seedUser(owner, "ml-unasg-owner", "未分配所有者");
        seedUser(worker, "ml-unasg-worker", "未分配执行者");
        seedProject(project, owner);
        seedMember(project, owner, "OWNER");
        seedMember(project, worker, "MEMBER");
        seedTask(uuid("2050"), project, "已分配任务", "TODO", worker, new BigDecimal("4.00"), NEXT_WEEK, owner);
        // 未分配：未完成（计入汇总）
        seedTask(uuid("2051"), project, "未分配待办", "TODO", null, new BigDecimal("6.00"), NEXT_WEEK, owner);
        seedTask(uuid("2052"), project, "未分配进行中", "IN_PROGRESS", null, null, null, owner);
        // 未分配：已完成（不计入"未完成未分配"汇总）
        seedTask(uuid("2053"), project, "未分配已完成", "DONE", null, new BigDecimal("9.00"), YESTERDAY, owner);

        MemberLoadView view = visualization.getMemberLoad(project, owner);

        // 未完成未分配 = 2（TODO + IN_PROGRESS），DONE 不计入
        assertThat(view.unassignedUncompletedTasks()).isEqualTo(2);
        // 未分配任务不得变成任何成员的负载，也不得出现"未分配"伪成员行
        assertThat(view.members()).hasSize(2);
        assertThat(view.members()).allSatisfy(load -> {
            assertThat(load.displayName()).isIn("未分配所有者", "未分配执行者");
            assertThat(load.userId()).isIn(owner, worker);
        });
        MemberLoadView.MemberLoad load = byUser(view, worker);
        assertThat(load.totalTasks()).isEqualTo(1);
        assertThat(load.uncompletedEstimateHours()).isEqualByComparingTo("4.00");
        // 所有者本身无任务但仍出现
        assertThat(view.members()).anySatisfy(o -> {
            assertThat(o.userId()).isEqualTo(owner);
            assertThat(o.totalTasks()).isZero();
        });
    }

    // ---------- 场景 7 + 9：多项目同成员 / 跨项目隔离 ----------

    @Test
    void sameMemberAcrossProjectsIsCountedPerProjectOnly() {
        UUID owner = uuid("0060");
        UUID shared = uuid("0061");
        UUID projectA = uuid("1060");
        UUID projectB = uuid("1061");
        seedUser(owner, "ml-cross-owner", "跨项目所有者");
        seedUser(shared, "ml-cross-shared", "跨项目共享者");
        seedProject(projectA, owner);
        seedProject(projectB, owner);
        seedMember(projectA, owner, "OWNER");
        seedMember(projectA, shared, "MEMBER");
        seedMember(projectB, owner, "OWNER");
        seedMember(projectB, shared, "MEMBER");
        seedTask(uuid("2060"), projectA, "甲项目任务一", "TODO", shared, new BigDecimal("3.00"), NEXT_WEEK, owner);
        seedTask(uuid("2061"), projectA, "甲项目任务二", "IN_PROGRESS", shared, new BigDecimal("2.50"), NEXT_WEEK, owner);
        // 乙项目的任务不得污染甲项目的统计
        seedTask(uuid("2062"), projectB, "乙项目任务", "TODO", shared, new BigDecimal("60.00"), NEXT_WEEK, owner);

        MemberLoadView viewA = visualization.getMemberLoad(projectA, shared);
        MemberLoadView.MemberLoad loadInA = byUser(viewA, shared);
        assertThat(loadInA.totalTasks()).isEqualTo(2);
        assertThat(loadInA.uncompletedTasks()).isEqualTo(2);
        assertThat(loadInA.uncompletedEstimateHours()).isEqualByComparingTo("5.50");
        assertThat(loadInA.totalEstimateHours()).isEqualByComparingTo("5.50");

        MemberLoadView viewB = visualization.getMemberLoad(projectB, shared);
        MemberLoadView.MemberLoad loadInB = byUser(viewB, shared);
        assertThat(loadInB.totalTasks()).isEqualTo(1);
        assertThat(loadInB.uncompletedEstimateHours()).isEqualByComparingTo("60.00");
        assertThat(viewB.unassignedUncompletedTasks()).isZero();
    }

    // ---------- 场景 8：不同角色权限隔离 ----------

    @Test
    void ownerAdminMemberCanReadButOutsiderIsRejected() {
        UUID owner = uuid("0070");
        UUID admin = uuid("0071");
        UUID member = uuid("0072");
        UUID outsider = uuid("0073");
        UUID project = uuid("1070");
        UUID otherProject = uuid("1071");
        seedUser(owner, "ml-role-owner", "角色所有者");
        seedUser(admin, "ml-role-admin", "角色管理员");
        seedUser(member, "ml-role-member", "角色成员");
        seedUser(outsider, "ml-role-outsider", "角色外部人");
        seedProject(project, owner);
        seedProject(otherProject, outsider);
        seedMember(project, owner, "OWNER");
        seedMember(project, admin, "ADMIN");
        seedMember(project, member, "MEMBER");
        seedMember(otherProject, outsider, "OWNER");
        seedTask(uuid("2070"), project, "角色任务", "TODO", member, new BigDecimal("4.00"), NEXT_WEEK, owner);

        // OWNER / ADMIN / MEMBER 均可读取，且读到同一份项目内数据
        for (UUID userId : List.of(owner, admin, member)) {
            MemberLoadView view = visualization.getMemberLoad(project, userId);
            assertThat(view.members()).hasSize(3);
            assertThat(byUser(view, member).uncompletedTasks()).isEqualTo(1);
        }

        // 非项目成员一律拒绝（403 语义），不得泄露任何项目内数据
        assertThatThrownBy(() -> visualization.getMemberLoad(project, outsider))
                .isInstanceOf(BusinessException.class);
        // 项目成员访问别的项目同样被拒绝
        assertThatThrownBy(() -> visualization.getMemberLoad(otherProject, member))
                .isInstanceOf(BusinessException.class);
    }

    // ---------- 种子数据助手 ----------

    private static UUID uuid(String tail) {
        return UUID.fromString("7e000000-0000-0000-0000-" + tail + "00000000");
    }

    private void seedUser(UUID id, String username, String displayName) {
        jdbc.update("""
                insert into app_user(id, username, password_hash, display_name)
                values (?, ?, 'memberload-test-hash', ?)
                """, id, username, displayName);
    }

    private void seedProject(UUID id, UUID ownerId) {
        jdbc.update("""
                insert into project(id, name, owner_id, created_by)
                values (?, ?, ?, ?)
                """, id, "成员负载测试项目-" + id, ownerId, ownerId);
    }

    private void seedMember(UUID projectId, UUID userId, String role) {
        jdbc.update("insert into project_member(project_id, user_id, role) values (?, ?, ?)",
                projectId, userId, role);
    }

    private void seedTask(UUID id, UUID projectId, String title, String status,
                          UUID assigneeId, BigDecimal estimateHours, LocalDate dueDate, UUID createdBy) {
        jdbc.update("""
                insert into project_task(id, project_id, title, status, priority,
                    assignee_id, estimate_hours, due_date, created_by)
                values (?, ?, ?, ?, 'MEDIUM', ?, ?, ?, ?)
                """, id, projectId, title, status, assigneeId, estimateHours, dueDate, createdBy);
    }

    private MemberLoadView.MemberLoad byUser(MemberLoadView view, UUID userId) {
        return view.members().stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("成员未出现在负载视图中: " + userId));
    }
}
