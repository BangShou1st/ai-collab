package com.shitulelv.aicollab.system.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentWorkerConcurrencyManager;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.api.dto.UpdateSystemConfigRequest;
import com.shitulelv.aicollab.system.application.view.SystemConfigAuditView;
import com.shitulelv.aicollab.system.application.view.SystemConfigView;
import com.shitulelv.aicollab.system.domain.model.SystemConfig;
import com.shitulelv.aicollab.system.domain.model.SystemConfigAudit;
import com.shitulelv.aicollab.system.infrastructure.repository.SystemConfigRepository;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SystemConfigServiceTest {
    private SystemConfigRepository repository;
    private UserService userService;
    private ObjectMapper objectMapper;
    private JdbcTemplate jdbcTemplate;
    private AgentWorkerConcurrencyManager concurrencyManager;
    private SystemConfigService service;

    private final UUID adminUserId = UUID.randomUUID();
    private final UUID regularUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(SystemConfigRepository.class);
        userService = mock(UserService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        jdbcTemplate = mock(JdbcTemplate.class);
        concurrencyManager = mock(AgentWorkerConcurrencyManager.class);

        com.shitulelv.aicollab.user.entity.UserEntity adminUser = mock(com.shitulelv.aicollab.user.entity.UserEntity.class);
        when(userService.requireSystemAdmin(adminUserId)).thenReturn(adminUser);
        when(userService.requireSystemAdmin(regularUserId))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_REQUIRED));

        service = new SystemConfigService(repository, userService, objectMapper, jdbcTemplate, true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "concurrencyManager", concurrencyManager);
    }

    @Test
    void nonAdminCannotReadConfig() {
        assertThatThrownBy(() -> service.getCurrentView(regularUserId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ADMIN_REQUIRED));
    }

    @Test
    void adminCanReadConfig() {
        SystemConfig current = SystemConfig.createDefault();
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        service.init();

        SystemConfigView view = service.getCurrentView(adminUserId);
        assertThat(view).isNotNull();
        assertThat(view.agentEnabled()).isFalse();
        assertThat(view.chatEnabled()).isFalse();
        assertThat(view.workerConcurrency()).isEqualTo(2);
    }

    @Test
    void validationRejectsOutOfRangeConcurrency() {
        UpdateSystemConfigRequest invalidRequest = new UpdateSystemConfigRequest(
                true, true, 5, 1000L, 30000L, false, "", true, "", "", 0.55, 12, 1L
        );
        assertThatThrownBy(() -> service.validateRequest(adminUserId, invalidRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Worker 并发数");
                });
    }

    @Test
    void validationRejectsOutOfRangeRagThreshold() {
        UpdateSystemConfigRequest invalidRequest = new UpdateSystemConfigRequest(
                true, true, 2, 1000L, 30000L, false, "", true, "", "", 1.5, 12, 1L
        );
        assertThatThrownBy(() -> service.validateRequest(adminUserId, invalidRequest))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("RAG 检索相似度阈值");
                });
    }

    @Test
    void optimisticLockRejectsOutdatedVersion() {
        SystemConfig current = SystemConfig.createDefault();
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        service.init();

        UpdateSystemConfigRequest request = new UpdateSystemConfigRequest(
                true, true, 3, 1000L, 30000L, false, "", true, "gpt-4o", "gpt-4o", 0.6, 15, 999L
        );

        assertThatThrownBy(() -> service.updateConfig(adminUserId, request, "admin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                });
    }

    @Test
    void successfulUpdateRecordsAuditAndAppliesToRuntime() {
        SystemConfig current = SystemConfig.createDefault();
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.updateWithVersion(any(), eq(1L))).thenReturn(true);
        service.init();

        UpdateSystemConfigRequest request = new UpdateSystemConfigRequest(
                true, true, 4, 1500L, 20000L, false, "", true, "gpt-4o", "gpt-4o", 0.6, 15, 1L
        );

        SystemConfigView updated = service.updateConfig(adminUserId, request, "admin_user");

        assertThat(updated.version()).isEqualTo(2L);
        assertThat(updated.agentEnabled()).isTrue();
        assertThat(updated.chatEnabled()).isTrue();
        assertThat(updated.workerConcurrency()).isEqualTo(4);

        // Verify runtime atomic reference updated
        assertThat(service.isAgentEnabled()).isTrue();
        assertThat(service.isChatEnabled()).isTrue();
        assertThat(service.getWorkerConcurrency()).isEqualTo(4);

        // Verify concurrency manager resized executor
        verify(concurrencyManager).setConcurrency(4);

        // Verify audit saved
        ArgumentCaptor<SystemConfigAudit> auditCaptor = ArgumentCaptor.forClass(SystemConfigAudit.class);
        verify(repository).saveAudit(auditCaptor.capture());
        SystemConfigAudit audit = auditCaptor.getValue();
        assertThat(audit.configVersion()).isEqualTo(2L);
        assertThat(audit.action()).isEqualTo("UPDATE");
        assertThat(audit.changedByName()).isEqualTo("admin_user");
        assertThat(audit.changeSummary()).contains("Agent开关");
    }

    @Test
    void restoreFromAuditSnapshot() throws Exception {
        SystemConfig current = SystemConfig.createDefault();
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        when(repository.updateWithVersion(any(), anyLong())).thenReturn(true);
        service.init();

        SystemConfig targetSnapshot = new SystemConfig(
                SystemConfig.DEFAULT_ID,
                true,
                true,
                3,
                1000L,
                30000L,
                false,
                "",
                true,
                "gpt-4o-mini",
                "gpt-4o",
                0.55,
                12,
                5L,
                adminUserId,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        String snapshotJson = objectMapper.writeValueAsString(targetSnapshot);
        UUID auditId = UUID.randomUUID();
        SystemConfigAudit historicalAudit = new SystemConfigAudit(
                auditId,
                5L,
                "UPDATE",
                adminUserId,
                "super_admin",
                "开启 Agent 与 Chat",
                snapshotJson,
                "APPLIED",
                null,
                OffsetDateTime.now().minusDays(1)
        );
        when(repository.findAuditById(auditId)).thenReturn(Optional.of(historicalAudit));

        SystemConfigView restored = service.restoreConfig(adminUserId, auditId, "admin");

        assertThat(restored.agentEnabled()).isTrue();
        assertThat(restored.chatEnabled()).isTrue();
        assertThat(restored.workerConcurrency()).isEqualTo(3);
        assertThat(restored.version()).isEqualTo(2L); // 1 + 1

        assertThat(service.isAgentEnabled()).isTrue();
        assertThat(service.isChatEnabled()).isTrue();
        verify(concurrencyManager).setConcurrency(3);
    }

    @Test
    void scheduledSyncPullsHigherVersionFromDatabase() {
        SystemConfig current = SystemConfig.createDefault(); // version 1
        when(repository.findCurrent()).thenReturn(Optional.of(current));
        service.init();
        assertThat(service.getActiveConfig().version()).isEqualTo(1L);

        // 模拟数据库中其他节点更新到了版本 2
        SystemConfig higherConfig = new SystemConfig(
                SystemConfig.DEFAULT_ID,
                true,
                true,
                4,
                1000L,
                30000L,
                false,
                "",
                true,
                "",
                "",
                0.55,
                12,
                2L,
                adminUserId,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        when(repository.findCurrent()).thenReturn(Optional.of(higherConfig));

        // 触发周期对账
        service.scheduledSync();

        assertThat(service.getActiveConfig().version()).isEqualTo(2L);
        assertThat(service.isAgentEnabled()).isTrue();
        assertThat(service.isChatEnabled()).isTrue();
        assertThat(service.getWorkerConcurrency()).isEqualTo(4);
        verify(concurrencyManager).setConcurrency(4);
    }

    @Test
    void reloadIgnoresLowerOrEqualVersionToPreventStaleOverwrite() {
        SystemConfig v2Config = new SystemConfig(
                SystemConfig.DEFAULT_ID,
                true,
                true,
                3,
                1000L,
                30000L,
                false,
                "",
                true,
                "",
                "",
                0.55,
                12,
                2L,
                adminUserId,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        when(repository.findCurrent()).thenReturn(Optional.of(v2Config));
        service.init();
        assertThat(service.getActiveConfig().version()).isEqualTo(2L);

        // 收到过期的版本 1
        SystemConfig staleConfig = SystemConfig.createDefault(); // version 1
        when(repository.findCurrent()).thenReturn(Optional.of(staleConfig));

        service.reloadFromDatabase();

        // 内存中仍然维持版本 2，没有被旧版本覆盖
        assertThat(service.getActiveConfig().version()).isEqualTo(2L);
        assertThat(service.isAgentEnabled()).isTrue();
    }
}
