package com.shitulelv.aicollab.system.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.system.api.dto.UpdateSystemConfigRequest;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.system.application.view.SystemConfigAuditView;
import com.shitulelv.aicollab.system.application.view.SystemConfigView;
import com.shitulelv.aicollab.system.application.view.SystemRuntimeStatusView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SystemConfigAdminControllerTest {
    private SystemConfigService service;
    private SystemConfigAdminController controller;

    private final UUID adminId = UUID.randomUUID();
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        service = mock(SystemConfigService.class);
        controller = new SystemConfigAdminController(service);

        jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(adminId.toString());
        when(jwt.getClaimAsString("username")).thenReturn("admin");
    }

    @Test
    void getConfigReturnsView() {
        SystemConfigView view = new SystemConfigView(
                UUID.randomUUID(), false, false, 2, 1000L, 30000L, false, "", true,
                "", "", 0.55, 12, 1L, adminId, OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(service.getCurrentView(adminId)).thenReturn(view);

        ApiResponse<SystemConfigView> response = controller.getConfig(jwt);
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data().version()).isEqualTo(1L);
    }

    @Test
    void updateConfigDelegatesToService() {
        UpdateSystemConfigRequest request = new UpdateSystemConfigRequest(
                true, true, 3, 1000L, 30000L, false, "", true, "m1", "m2", 0.6, 10, 1L
        );
        SystemConfigView updated = new SystemConfigView(
                UUID.randomUUID(), true, true, 3, 1000L, 30000L, false, "", true,
                "m1", "m2", 0.6, 10, 2L, adminId, OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(service.updateConfig(eq(adminId), any(), eq("admin"))).thenReturn(updated);

        ApiResponse<SystemConfigView> response = controller.updateConfig(request, jwt);
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data().version()).isEqualTo(2L);
        assertThat(response.data().agentEnabled()).isTrue();
    }

    @Test
    void restoreConfigDelegatesToService() {
        UUID auditId = UUID.randomUUID();
        SystemConfigView restored = new SystemConfigView(
                UUID.randomUUID(), false, false, 2, 1000L, 30000L, false, "", true,
                "", "", 0.55, 12, 3L, adminId, OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(service.restoreConfig(eq(adminId), eq(auditId), eq("admin"))).thenReturn(restored);

        ApiResponse<SystemConfigView> response = controller.restoreConfig(auditId, jwt);
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data().version()).isEqualTo(3L);
    }

    @Test
    void getRuntimeStatusReturnsDiagnostics() {
        SystemRuntimeStatusView status = new SystemRuntimeStatusView(
                true, true, true, 3, 1, 0, 1, 0, null, false, OffsetDateTime.now()
        );
        when(service.getRuntimeStatus(adminId)).thenReturn(status);

        ApiResponse<SystemRuntimeStatusView> response = controller.getRuntimeStatus(jwt);
        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data().activeWorkerThreads()).isEqualTo(1);
    }
}
