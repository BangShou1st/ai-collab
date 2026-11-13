package com.shitulelv.aicollab.auth.service;

import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSessionStateServiceTest {

    @Test
    void active_state_is_cached_until_explicitly_evicted() {
        UserService users = mock(UserService.class);
        UserSessionStateService service = new UserSessionStateService(users);
        UserEntity user = user(UserStatus.ACTIVE, 4);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.find(user.getId())).get()
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(UserStatus.ACTIVE);
                    assertThat(state.tokenVersion()).isEqualTo(4);
                });
        assertThat(service.find(user.getId())).isPresent();
        verify(users, times(1)).findById(user.getId());

        service.evict(user.getId());
        assertThat(service.find(user.getId())).isPresent();
        verify(users, times(2)).findById(user.getId());
    }

    @Test
    void unknown_user_is_not_cached_and_returns_empty() {
        UserService users = mock(UserService.class);
        UserSessionStateService service = new UserSessionStateService(users);
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.find(userId)).isEmpty();
        assertThat(service.find(userId)).isEmpty();
        verify(users, times(2)).findById(userId);
    }

    private static UserEntity user(UserStatus status, int tokenVersion) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
