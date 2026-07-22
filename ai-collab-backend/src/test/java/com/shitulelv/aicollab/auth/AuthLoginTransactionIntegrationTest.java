package com.shitulelv.aicollab.auth;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.auth.service.AccessTokenService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录编排必须是一个真实数据库事务：Access Token 签发失败时，已更新的登录时间必须回滚，
 * 且失败点之后的 Refresh Token 会话创建不会执行。
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30"
})
@AutoConfigureMockMvc
class AuthLoginTransactionIntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private AccessTokenService accessTokenService;

    private UserEntity user;

    @BeforeEach
    void createUser() {
        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("login_transaction_" + user.getId().toString().substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode("transaction-password"));
        user.setDisplayName("Transaction User");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        userMapper.insert(user);
    }

    @AfterEach
    void removeUser() {
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", user.getId());
        userMapper.deleteById(user.getId());
    }

    @Test
    void rollsBackLastLoginWhenAccessTokenSigningFailsBeforeRefreshSessionCreation() throws Exception {
        when(accessTokenService.createAccessToken(any(UserEntity.class)))
                .thenThrow(new IllegalStateException("签发失败"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType("application/json")
                        .content("{\"username\":\"" + user.getUsername()
                                + "\",\"password\":\"transaction-password\"}"))
                .andExpect(status().isInternalServerError());

        assertThat(userMapper.selectById(user.getId()).getLastLoginAt()).isNull();
        Integer refreshTokenCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ?", Integer.class, user.getId());
        assertThat(refreshTokenCount).isZero();
    }
}
