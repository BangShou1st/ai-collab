package com.shitulelv.aicollab.agent.infrastructure;

import com.shitulelv.aicollab.agent.api.dto.AgentMemoryRequest;
import com.shitulelv.aicollab.agent.application.AgentMemoryService;
import com.shitulelv.aicollab.agent.application.AgentMemorySourceVerifier;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * AC07-P1 第一波集成验证:V87 迁移默认值、M-09 来源校验、验证状态生命周期与召回过滤。
 * 全部走真实 PostgreSQL + Flyway V1-V87 + 真实 Repository/Service,权限守卫用 mock,零模型调用。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentMemoryVerificationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test
    void verificationStateLifecycleAndSourceGovernance() {
        DataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        // 先迁移到 V86,插入"升级前"legacy 行,再迁到 V87,验证存量默认 UNVERIFIED 且不被伪造升级
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("86").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID user = UUID.randomUUID(), project = UUID.randomUUID(), otherProject = UUID.randomUUID();
        UUID document = UUID.randomUUID(), otherProjectDocument = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,'memory-admin','unused','test')", user);
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'one',?,?),(?,'two',?,?)",
                project, user, user, otherProject, user, user);
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,uploaded_by) " +
                        "VALUES (?,?,'doc','doc.txt','text/plain',1,?,'READY',?),(?,?,'other','other.txt','text/plain',1,?,'READY',?)",
                document, project, "projects/" + project + "/documents/" + document + "/source.txt", user,
                otherProjectDocument, otherProject, "projects/" + otherProject + "/documents/" + otherProjectDocument + "/source.txt", user);
        UUID legacyMemory = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_memory(id,project_id,type,title,content,source_type,created_by,updated_by) " +
                        "VALUES (?,?,'DECISION','legacy decision','legacy content','MANUAL',?,?)",
                legacyMemory, project, user, user);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        // ---- V87 迁移:存量行默认 UNVERIFIED,verified_at/verified_by 为空,不得静默升级 ----
        assertThat(jdbc.queryForObject("SELECT verification_state FROM agent_memory WHERE id=?", String.class, legacyMemory))
                .isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject("SELECT verified_at IS NULL AND verified_by IS NULL FROM agent_memory WHERE id=?",
                Boolean.class, legacyMemory)).isTrue();
        // 新行同样默认 UNVERIFIED
        jdbc.update("INSERT INTO agent_memory(id,project_id,type,title,content,source_type,created_by,updated_by) " +
                        "VALUES (?,?,'LESSON','new default','content','MANUAL',?,?)",
                UUID.randomUUID(), project, user, user);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_memory WHERE project_id=? AND verification_state='UNVERIFIED'",
                Long.class, project)).isEqualTo(2L);
        // CHECK 约束兜底:非法状态无法写入
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE agent_memory SET verification_state='WHATEVER' WHERE id=?", legacyMemory))
                .isInstanceOf(DataIntegrityViolationException.class);

        // ---- 真实 Service 装配(权限守卫 mock) ----
        var access = mock(ProjectAccessGuard.class);
        var service = new AgentMemoryService(new AgentMemoryRepository(jdbc), access,
                new AgentMemorySourceVerifier(new AgentMemorySourceRepository(jdbc)));

        // ---- M-09:未知 sourceType 拒绝,不静默接受 ----
        assertThatThrownBy(() -> service.create(project, user, request("DECISION", "伪造类型", "I_AM_VERIFIED", null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SOURCE_TYPE_INVALID));

        // ---- M-09:DOCUMENT 来源不存在 / 跨项目 拒绝 ----
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(project, user, request("DECISION", "伪造来源", "DOCUMENT", missing, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SOURCE_INVALID));
        assertThatThrownBy(() -> service.create(project, user, request("DECISION", "跨项目来源", "DOCUMENT", otherProjectDocument, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SOURCE_INVALID));
        // 更新路径同样拒绝
        AgentMemoryView created = service.create(project, user, request("DECISION", "数据库选型", "MANUAL", null, null));
        assertThatThrownBy(() -> service.update(project, created.id(), user,
                request("DECISION", "改成跨项目来源", "DOCUMENT", otherProjectDocument, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_SOURCE_INVALID));

        // ---- M-09:合法 DOCUMENT 来源创建成功,恒为 UNVERIFIED ----
        AgentMemoryView fromDocument = service.create(project, user,
                request("CONSTRAINT", "依据文档的约束", "DOCUMENT", document, null));
        assertThat(fromDocument.verificationState()).isEqualTo("UNVERIFIED");
        assertThat(fromDocument.verifiedAt()).isNull();
        assertThat(fromDocument.verifiedBy()).isNull();
        // 创建路径不接受客户端指定验证状态(防伪造 VERIFIED)
        assertThatThrownBy(() -> service.create(project, user, request("DECISION", "自带状态", "MANUAL", null, "VERIFIED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_VERIFICATION_INVALID));

        // ---- 生命周期:update 置 VERIFIED 记录 verified_at/verified_by;非法值拒绝 ----
        AgentMemoryView verified = service.update(project, fromDocument.id(), user,
                request(fromDocument, "CONSTRAINT", "依据文档的约束", "DOCUMENT", document, "VERIFIED"));
        assertThat(verified.verificationState()).isEqualTo("VERIFIED");
        assertThat(verified.verifiedAt()).isNotNull();
        assertThat(verified.verifiedBy()).isEqualTo(user);
        assertThatThrownBy(() -> service.update(project, fromDocument.id(), user,
                request(verified, "CONSTRAINT", "依据文档的约束", "DOCUMENT", document, "CONFLICTED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AGENT_MEMORY_VERIFICATION_INVALID));

        // ---- 生命周期:来源变更即使请求 VERIFIED 也强制重置 UNVERIFIED(新来源必须重新核验) ----
        AgentMemoryView reset = service.update(project, fromDocument.id(), user,
                request(verified, "CONSTRAINT", "换来源还带状态", "MANUAL", null, "VERIFIED"));
        assertThat(reset.verificationState()).isEqualTo("UNVERIFIED");
        assertThat(reset.verifiedAt()).isNull();
        assertThat(reset.verifiedBy()).isNull();

        // ---- 生命周期:STALE 保留最近一次核验记录;update 置回 VERIFIED 可恢复 ----
        AgentMemoryView reVerified = service.update(project, fromDocument.id(), user,
                request(reset, "CONSTRAINT", "依据文档的约束", "MANUAL", null, "VERIFIED"));
        assertThat(reVerified.verificationState()).isEqualTo("VERIFIED");
        AgentMemoryView stale = service.update(project, fromDocument.id(), user,
                request(reVerified, "CONSTRAINT", "依据文档的约束", "MANUAL", null, "STALE"));
        assertThat(stale.verificationState()).isEqualTo("STALE");
        assertThat(stale.verifiedAt()).isNotNull();
        assertThat(stale.verifiedBy()).isEqualTo(user);
        AgentMemoryView reVerifiedAgain = service.update(project, fromDocument.id(), user,
                request(stale, "CONSTRAINT", "依据文档的约束", "MANUAL", null, "VERIFIED"));
        assertThat(reVerifiedAgain.verificationState()).isEqualTo("VERIFIED");

        // ---- 召回过滤:STALE/CONFLICTED/SOURCE_REVOKED 不出现在 list(activeOnly=true)/context();UNVERIFIED/VERIFIED 保留 ----
        AgentMemoryView manual = service.create(project, user, request("PREFERENCE", "展示偏好偏好", "MANUAL", null, null));
        jdbc.update("UPDATE agent_memory SET verification_state='STALE' WHERE id=?", created.id());
        jdbc.update("UPDATE agent_memory SET verification_state='CONFLICTED' WHERE id=?", legacyMemory);
        UUID revoked = service.create(project, user, request("PREFERENCE", "已撤权偏好", "MANUAL", null, null)).id();
        jdbc.update("UPDATE agent_memory SET verification_state='SOURCE_REVOKED' WHERE id=?", revoked);
        // PREFERENCE 类型无需词项命中即可通过召回打分,直接检验验证状态过滤维度
        List<AgentMemoryView> recall = service.list(project, user, true);
        assertThat(recall).extracting(AgentMemoryView::id)
                .contains(manual.id(), fromDocument.id())
                .doesNotContain(created.id(), legacyMemory, revoked);
        assertThat(service.context(project, user, "任意目标")).extracting(AgentMemoryView::id)
                .doesNotContain(created.id(), legacyMemory, revoked);
        // 管理视图(activeOnly=false)不过滤,便于管理员复核失效记录
        assertThat(service.list(project, user, false)).extracting(AgentMemoryView::id)
                .contains(manual.id(), fromDocument.id(), created.id(), legacyMemory, revoked);

        // ---- UNVERIFIED 参与词项召回(legacy 兼容) ----
        AgentMemoryView unverifiedDecision = service.create(project, user,
                request("DECISION", "统一使用 PostgreSQL", "MANUAL", null, null));
        List<AgentMemoryView> matched = service.context(project, user, "数据库用什么?PostgreSQL");
        assertThat(matched).extracting(AgentMemoryView::id).contains(unverifiedDecision.id());

        // ---- CRUD 回归:disable 后 activeOnly 视图不可见;disable 可同时携带验证状态转移 ----
        AgentMemoryView disabled = service.disable(project, unverifiedDecision.id(), user, 0, "STALE");
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(disabled.verificationState()).isEqualTo("STALE");
        assertThat(service.list(project, user, true)).extracting(AgentMemoryView::id)
                .doesNotContain(unverifiedDecision.id());
        AgentMemoryView plainDisabled = service.disable(project, manual.id(), user, 0);
        assertThat(plainDisabled.status()).isEqualTo("DISABLED");
        // 版本冲突仍被拒绝(CAS 语义未破坏)
        assertThatThrownBy(() -> service.disable(project, fromDocument.id(), user, 0, "STALE"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
    }

    private static AgentMemoryRequest request(String type, String title, String sourceType, UUID sourceId,
                                              String verificationState) {
        return new AgentMemoryRequest(type, title, title + " 内容", sourceType, sourceId, 0, verificationState);
    }

    /** 携带当前最新 version 的更新请求(CAS)。 */
    private static AgentMemoryRequest request(AgentMemoryView current, String type, String title,
                                              String sourceType, UUID sourceId, String verificationState) {
        return new AgentMemoryRequest(type, title, title + " 内容", sourceType, sourceId,
                current.version(), verificationState);
    }
}
