package com.shitulelv.aicollab.user.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.application.service.AuditService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminServiceAuthorizationTest {

    @Test
    void normalUserCannotCreateTestAccounts() {
        UserMapper userMapper = mock(UserMapper.class);
        UserService users = new UserService(userMapper);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        UUID operatorId = UUID.randomUUID();
        UserEntity operator = new UserEntity();
        operator.setId(operatorId);
        operator.setSystemAdmin(false);
        when(userMapper.selectById(operatorId)).thenReturn(operator);

        AdminService service = new AdminService(users, passwords, audit);

        assertThatThrownBy(() ->
                service.createTestUser("tester", "Tester", "password", operatorId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.ADMIN_REQUIRED));
    }
}
