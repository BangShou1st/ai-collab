package com.shitulelv.aicollab.user.mapper;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.model.UserStatus;
import com.shitulelv.aicollab.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
@Transactional
class UserMapperIntegrationTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserService userService;

    @Test
    void mapsUuidEnumAndOffsetDateTimeAgainstRealPostgres() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername("integration_" + id.toString().substring(0, 8));
        user.setPasswordHash("$2a$12$test-only-hash");
        user.setDisplayName("Integration User");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);

        userMapper.insert(user);
        OffsetDateTime loginAt = OffsetDateTime.of(2026, 7, 20, 15, 0, 0, 0, ZoneOffset.UTC);
        userService.updateLastLoginAt(id, loginAt);
        UserEntity reloaded = userMapper.selectById(id);

        assertThat(reloaded.getId()).isEqualTo(id);
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reloaded.getLastLoginAt()).isEqualTo(loginAt);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }
}
