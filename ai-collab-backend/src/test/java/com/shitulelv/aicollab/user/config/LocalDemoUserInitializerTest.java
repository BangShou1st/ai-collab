package com.shitulelv.aicollab.user.config;

import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDemoUserInitializerTest {

    @Test
    void runningTwiceCreatesOneUserWithPasswordHash() throws Exception {
        UserService userService = mock(UserService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        ApplicationArguments arguments = mock(ApplicationArguments.class);
        when(userService.findByUsername("owner"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new UserEntity()));
        when(passwordEncoder.encode("12345678")).thenReturn("$2a$12$hashed-password");
        LocalDemoUserInitializer initializer = new LocalDemoUserInitializer(
                userService,
                passwordEncoder,
                new LocalDemoUserProperties("owner", "12345678"));

        initializer.run(arguments);
        initializer.run(arguments);

        org.mockito.ArgumentCaptor<UserEntity> captor = org.mockito.ArgumentCaptor.forClass(UserEntity.class);
        verify(userService, times(1)).create(captor.capture());
        UserEntity created = captor.getValue();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getUsername()).isEqualTo("owner");
        assertThat(created.getPasswordHash()).isEqualTo("$2a$12$hashed-password");
        assertThat(created.getPasswordHash()).doesNotContain("12345678");
        assertThat(created.getDisplayName()).isEqualTo("Local Owner");
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(passwordEncoder, times(1)).encode("12345678");
        verify(userService, times(2)).findByUsername("owner");
    }
}
