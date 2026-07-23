package com.shitulelv.aicollab.project;

import com.shitulelv.aicollab.TestcontainersConfiguration;
import com.shitulelv.aicollab.project.application.service.InvitationCodeService;
import com.shitulelv.aicollab.user.entity.UserEntity;
import com.shitulelv.aicollab.user.mapper.UserMapper;
import com.shitulelv.aicollab.user.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "security.jwt.secret=test-only-secret-0123456789012345",
        "security.jwt.access-token-minutes=30",
        "security.refresh-token.cookie-secure=false"
})
@AutoConfigureMockMvc
class ProjectApiIntegrationTest {

    private static final String PASSWORD = "integration-password";
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired InvitationCodeService invitationCodes;
    @Autowired UserMapper userMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM project");
        jdbcTemplate.update("DELETE FROM refresh_token");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    @Test
    void projectCrudIsScopedToMembershipAndUsesOptimisticVersion() throws Exception {
        UserEntity owner = createUser("owner");
        UserEntity outsider = createUser("outsider");
        String ownerToken = login(owner);
        String outsiderToken = login(outsider);

        String created = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Project One","description":"Scoped","startDate":"2026-08-01","dueDate":"2026-08-31"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        UUID projectId = UUID.fromString(objectMapper.readTree(created).path("data").path("id").stringValue());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ? AND role = 'OWNER'",
                Integer.class, projectId, owner.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE project_id = ? AND action = 'PROJECT_CREATED'",
                Integer.class, projectId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/projects/{id}", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated","description":"","startDate":"2026-08-01",
                                 "dueDate":"2026-09-01","status":"ACTIVE","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Stale","description":"","status":"ACTIVE","version":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void invitationStoresOnlyHashAndCanBeAcceptedExactlyOnce() throws Exception {
        UserEntity owner = createUser("inviter");
        String ownerToken = login(owner);
        UUID projectId = createProject(ownerToken);

        String created = mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"MEMBER","invitedEmail":"new@example.com","expiresInHours":72}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(created).path("data").path("code").stringValue();
        assertThat(code.length()).isEqualTo(43);
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT invite_code_hash FROM project_invitation WHERE project_id = ?",
                String.class, projectId);
        assertThat(storedHash.length()).isEqualTo(64);
        assertThat(storedHash.equals(invitationCodes.hash(code))).isTrue();

        mockMvc.perform(get("/api/v1/invitations/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectName").value("Invite Project"))
                .andExpect(jsonPath("$.data.invitedEmail").value("new@example.com"));

        String acceptBody = """
                {"username":"new_member","password":"new-password","displayName":"New Member","email":"new@example.com"}
                """;
        mockMvc.perform(post("/api/v1/invitations/{code}/accept", code)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.user.username").value("new_member"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_member pm JOIN app_user u ON u.id = pm.user_id " +
                        "WHERE pm.project_id = ? AND u.username = 'new_member' AND pm.role = 'MEMBER'",
                Integer.class, projectId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/invitations/{code}/accept", code)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptBody.replace("new_member", "second_member")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
    }

    @Test
    void ownerProtectsMembershipWhileAdminCanInviteAndMemberCannot() throws Exception {
        UserEntity owner = createUser("membership_owner");
        UserEntity admin = createUser("membership_admin");
        UserEntity member = createUser("membership_member");
        String ownerToken = login(owner);
        String adminToken = login(admin);
        String memberToken = login(member);
        UUID projectId = createProject(ownerToken);
        addMember(projectId, admin.getId(), "ADMIN");
        addMember(projectId, member.getId(), "MEMBER");

        mockMvc.perform(get("/api/v1/projects/{id}/members", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\",\"expiresInHours\":24}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\",\"expiresInHours\":24}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ADMIN_REQUIRED"));

        mockMvc.perform(patch("/api/v1/projects/{id}/members/{userId}/role", projectId, member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        mockMvc.perform(patch("/api/v1/projects/{id}/members/{userId}/role", projectId, owner.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_OWNER_CANNOT_BE_REMOVED"));
        mockMvc.perform(delete("/api/v1/projects/{id}/members/{userId}", projectId, owner.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{userId}", projectId, member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isNoContent());
        assertThat(userMapper.selectById(member.getId())).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ?",
                Integer.class, projectId, member.getId())).isZero();
    }

    @Test
    void invitationAcceptRequiresTrustedOriginAndExpiredInvitationReturnsGone() throws Exception {
        UserEntity owner = createUser("origin_owner");
        UUID projectId = createProject(login(owner));
        String created = mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(login(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\",\"expiresInHours\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(created).path("data").path("code").stringValue();

        mockMvc.perform(post("/api/v1/invitations/{code}/accept", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"origin_user","password":"new-password","displayName":"Origin User"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        jdbcTemplate.update(
                "UPDATE project_invitation SET expires_at = now() - interval '1 second' WHERE project_id = ?",
                projectId);
        mockMvc.perform(get("/api/v1/invitations/{code}", code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
    }

    @Test
    void deletingProjectKeepsSanitizedDeleteAuditWithoutProjectForeignKey() throws Exception {
        UserEntity owner = createUser("delete_owner");
        String token = login(owner);
        UUID projectId = createProject(token);

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE project_id IS NULL AND entity_id = ? " +
                        "AND action = 'PROJECT_DELETED' AND detail = '{}'::jsonb",
                Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    void pendingInvitationRemainsPreviewableAfterItsAdminCreatorIsRemoved() throws Exception {
        UserEntity owner = createUser("preview_owner");
        UserEntity admin = createUser("preview_admin");
        String ownerToken = login(owner);
        String adminToken = login(admin);
        UUID projectId = createProject(ownerToken);
        addMember(projectId, admin.getId(), "ADMIN");

        String created = mockMvc.perform(post("/api/v1/projects/{id}/invitations", projectId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\",\"expiresInHours\":24}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(created).path("data").path("code").stringValue();
        mockMvc.perform(delete("/api/v1/projects/{id}/members/{userId}", projectId, admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/invitations/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectName").value("Invite Project"));
    }

    private UUID createProject(String token) throws Exception {
        String response = mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Invite Project\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("data").path("id").stringValue());
    }

    private UserEntity createUser(String prefix) {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(prefix + "_" + id.toString().substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDisplayName(prefix);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user;
    }

    private void addMember(UUID projectId, UUID userId, String role) {
        jdbcTemplate.update(
                "INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, ?)",
                projectId, userId, role);
    }

    private String login(UserEntity user) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", user.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").stringValue();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
