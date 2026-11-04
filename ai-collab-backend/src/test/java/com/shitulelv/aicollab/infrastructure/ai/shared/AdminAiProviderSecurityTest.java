package com.shitulelv.aicollab.infrastructure.ai.shared;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.shared.api.AdminAiProviderController;
import com.shitulelv.aicollab.infrastructure.ai.user.api.UserAiProviderRequest;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAiProviderSecurityTest {

    @Mock
    private AdminAiProviderService adminAiService;
    @Mock
    private UserService userService;

    @InjectMocks
    private AdminAiProviderController controller;

    private final UUID regularUserId = UUID.randomUUID();

    @Test
    @DisplayName("普通用户直接调用管理员 AI 供应商接口必须被 403 拦截")
    void nonAdminCallingAdminControllerIsRejected() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(regularUserId.toString());
        doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN))
                .when(userService).requireSystemAdmin(regularUserId);

        assertThatThrownBy(() -> controller.list(jwt))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.AUTH_FORBIDDEN);

        assertThatThrownBy(() -> controller.publishShared(UUID.randomUUID(), jwt))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.AUTH_FORBIDDEN);

        assertThatThrownBy(() -> controller.test(UUID.randomUUID(), jwt))
                .isInstanceOf(BusinessException.class)
                .matches(e -> ((BusinessException) e).getErrorCode() == ErrorCode.AUTH_FORBIDDEN);

        verifyNoInteractions(adminAiService);
    }
}
