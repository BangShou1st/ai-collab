package com.shitulelv.aicollab.system.api.controller;

import com.shitulelv.aicollab.auth.service.PublicRegistrationService;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicSystemStatusControllerTest {

    @Test
    void statusExposesMaintenanceBannerOnlyWhenEnabled() {
        SystemConfigService configService = mock(SystemConfigService.class);
        PublicRegistrationService registrations = mock(PublicRegistrationService.class);
        PublicSystemStatusController controller = new PublicSystemStatusController(configService, registrations);

        when(configService.isMaintenanceMode()).thenReturn(true);
        when(configService.effectiveMaintenanceMessage()).thenReturn("停站升级中");
        when(registrations.isEnabled()).thenReturn(false);

        var res = controller.status().data();
        assertThat(res.maintenanceMode()).isTrue();
        assertThat(res.maintenanceMessage()).isEqualTo("停站升级中");
        assertThat(res.publicRegistrationEnabled()).isFalse();

        when(configService.isMaintenanceMode()).thenReturn(false);
        when(registrations.isEnabled()).thenReturn(true);
        var normal = controller.status().data();
        assertThat(normal.maintenanceMode()).isFalse();
        assertThat(normal.maintenanceMessage()).isEmpty();
        assertThat(normal.publicRegistrationEnabled()).isTrue();
    }
}
