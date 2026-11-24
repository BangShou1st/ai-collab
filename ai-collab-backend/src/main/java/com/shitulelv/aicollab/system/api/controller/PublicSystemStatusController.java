package com.shitulelv.aicollab.system.api.controller;

import com.shitulelv.aicollab.auth.service.PublicRegistrationService;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import com.shitulelv.aicollab.system.application.view.PublicSystemStatusView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开系统状态查询接口（无需鉴权），供前端在登录前及收到 503 维护响应后获取停站公告与注册开关。
 */
@RestController
@RequestMapping("/api/v1/system")
public class PublicSystemStatusController {

    private final SystemConfigService configService;
    private final PublicRegistrationService registrations;

    public PublicSystemStatusController(
            SystemConfigService configService,
            PublicRegistrationService registrations) {
        this.configService = configService;
        this.registrations = registrations;
    }

    @GetMapping("/status")
    public ApiResponse<PublicSystemStatusView> status() {
        boolean maintenanceMode = configService.isMaintenanceMode();
        String maintenanceMessage = maintenanceMode ? configService.effectiveMaintenanceMessage() : "";
        return ApiResponse.success(new PublicSystemStatusView(
                maintenanceMode,
                maintenanceMessage,
                registrations.isEnabled()));
    }
}
